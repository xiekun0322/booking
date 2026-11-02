package com.starscreen.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starscreen.common.BusinessException;
import com.starscreen.common.StatusConstants;
import com.starscreen.entity.Movie;
import com.starscreen.entity.Order;
import com.starscreen.entity.Schedule;
import com.starscreen.entity.Seat;
import com.starscreen.repository.MovieRepository;
import com.starscreen.repository.OrderRepository;
import com.starscreen.repository.ScheduleRepository;
import com.starscreen.repository.SeatRepository;
import com.starscreen.util.SnowflakeIdWorker;

import lombok.RequiredArgsConstructor;

/**
 * 【功能】订单业务服务 —— 系统核心。
 *         负责购票全链路：
 *           1. 下单锁座  createOrder
 *           2. 支付出票  pay
 *           3. 主动取消  cancel
 *           4. 超时释放  cancelExpiredOrders（@Scheduled 每 30 秒）
 *           5. 订单查询  listPagedByUser / listPaged / getById
 *
 * 【座位状态机】
 *          available → locked   ：createOrder（原子条件 UPDATE）
 *          locked    → sold     ：pay
 *          locked    → available：cancel / cancelExpiredOrders
 *
 * 【订单状态机】
 *          pending → paid        ：pay
 *          pending → cancelled   ：cancel / cancelExpiredOrders
 *          paid    → （不可取消）
 *
 * 【并发安全】
 *          createOrder 的"锁座"改用 SeatRepository.lockSeat() 条件 UPDATE，
 *          WHERE status='available' 由数据库保证原子性，
 *          解决了"查 available → 改 locked"之间的超卖窗口。
 *
 * 【事务设计】
 *          - createOrder / pay / cancel / cancelExpiredOrders 全部 @Transactional
 *          - 抛 BusinessException（RuntimeException）→ 自动回滚
 *          - 场景示例：createOrder 中锁第 2 个座位失败 → 第 1 个座位锁也被回滚
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    /** 支付超时时间（分钟）—— 超时后订单自动取消，座位释放 */
    private static final int PAY_TIMEOUT_MINUTES = 10;

    /** 时间格式，与 Order 实体里的字符串字段保持一致 */
    private static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 座位标签正则：必须匹配 "3排5座" 格式 */
    private static final Pattern SEAT_LABEL = Pattern.compile("^(\\d+)排(\\d+)座$");

    /** 单笔订单最多座位数（与 CreateOrderRequest 的 @Size(max=6) 呼应） */
    private static final int MAX_SEATS_PER_ORDER = 6;

    /** 订单号 / 取票码生成用的随机数 */
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

    /**
     * 取票码字符集：去掉 0/O/1/I/L 等易混字符。
     * 取票码是人工核验的，看错一个字符就要重新查订单。
     */
    private static final char[] TICKET_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private final OrderRepository orderRepository;
    private final SeatRepository seatRepository;
    private final ScheduleRepository scheduleRepository;
    private final MovieRepository movieRepository;
    /** RabbitTemplate 用于发送延迟消息，触发超时订单取消 */
    private final RabbitTemplate rabbitTemplate;
    
    private static final SnowflakeIdWorker snowflake = new SnowflakeIdWorker(1); // 机器id：1
    
    private final SmsService smsService; // 加上这行，因为类上面有 @RequiredArgsConstructor，它会自动注入
    
    private static final String MSG_ORDER_NOT_FOUND = "订单不存在";
    
    // ==================== 1. 下单锁座 ====================

    /**
     * 【功能】创建订单并锁定座位（核心）。
     * 【调用链】
     *   OrderController.create(@Valid CreateOrderRequest, HttpSession)
     *   → OrderService.createOrder(userId, scheduleId, seatLabels)
     *   → scheduleRepository.findById      查场次
     *   → seatRepository.findBy...         逐个查座位（校验 available）
     *   → orderRepository.save             插入订单
     *   → seatRepository.lockSeat          原子锁座（条件 UPDATE）
     *   → 返回 Order 给前端 → 跳转 /order/{id} 支付页
     *
     * 【步骤详解】
     *   1) 校验参数（userId / scheduleId / seatLabels 非空、座位数 ≤ 6）
     *   2) 查场次（不存在 → 抛异常）
     *   3) 逐个查座位并检查 status == "available"（只查，不锁）
     *   4) 构造 Order 实体（快照电影名、影院、场次、座位、总价）
     *   5) 保存订单 → 拿到 order.id
     *   6) 用 lockSeat 条件 UPDATE 原子锁座（★ 并发安全的关键）
     *
     * 【事务保证】
     *   若第 6 步中某个座位抢不到（affected == 0），
     *   抛 BusinessException → 整个方法回滚，
     *   已保存的订单和已锁的座位全部撤销。
     *
     * 【异常】
     *   - "请先登录"          userId 为空
     *   - "场次 ID 不能为空"   scheduleId 为空
     *   - "请至少选择一个座位"  seats 为空
     *   - "一次最多选择 6 个座位"
     *   - "座位数据异常：请勿重复提交相同座位" 同一座位在本次请求里出现多次（参数校验阶段拦截）
     *   - "场次不存在"
     *   - "座位不存在：xxx"   座位标签格式对但数据库没这个座位
     *   - "座位已被选走：xxx" 座位状态不是 available（预检）
     *   - "座位已被选走：xxx" 条件 UPDATE 影响行数为 0（并发抢座失败）
     *   - "座位格式错误：xxx" 标签不符合 ^\d+排\d+座$
     */
    @Transactional
//    public Order createOrder(Long userId, Long scheduleId, List<String> seatLabels) {
    public Order createOrder(Long userId, Long scheduleId, List<String> seatLabels, String captchaTicket) {
        // -------- 步骤 1：参数校验 --------
    	validateOrderParams(userId, scheduleId, seatLabels, captchaTicket);

        // -------- 步骤 2：查场次 --------
        Schedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException("场次不存在"));

        // -------- 步骤 3：逐座位校验（只查，不锁）--------
        List<Seat> seats = new ArrayList<>();
        for (String label : seatLabels) {
            int[] rc = parseSeatLabel(label);                       // "3排5座" → [3, 5]
            Seat seat = seatRepository
                    .findByScheduleIdAndRowNumAndColNum(scheduleId, rc[0], rc[1])
                    .orElseThrow(() -> new BusinessException("座位不存在：" + label));
            if (!StatusConstants.SEAT_AVAILABLE.equals(seat.getStatus()))
                throw new BusinessException("座位已被选走：" + label);
            seats.add(seat);
        }

        // -------- 步骤 4：构造订单（快照）--------
        Movie movie = movieRepository.findById(schedule.getMovieId()).orElse(null);

        Order order = new Order();
        order.setOrderNo(generateOrderNo());
        order.setUserId(userId);
        order.setScheduleId(scheduleId);
        order.setMovieId(schedule.getMovieId());
        order.setMovieTitle(movie != null ? movie.getTitle() : "未知电影");
        order.setCinemaName(schedule.getCinemaName());
        order.setHallName(schedule.getHallName());
        String dateTimeStr = schedule.getDate() + " " + schedule.getStartTime();
        order.setShowTime(LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));
        order.setSeats(String.join(",", seatLabels));
        // 1. 取出单价，把 Double 转成 BigDecimal（如果 schedule.getPrice() 已经是 BigDecimal，这行可以不写，直接赋值）
        // 1. 取出单价，如果为空默认 35.0
        Double priceObj = schedule.getPrice() != null ? schedule.getPrice() : 35.0;
        BigDecimal unitPrice = BigDecimal.valueOf(priceObj);
        // 2. 把座位数量转成 BigDecimal
        BigDecimal seatCount = new BigDecimal(seatLabels.size());
        // 3. 相乘，并保留两位小数，四舍五入
        BigDecimal totalPrice = unitPrice.multiply(seatCount).setScale(2, RoundingMode.HALF_UP);
        // 4. 赋值给订单
        order.setTotalPrice(totalPrice);
        order.setStatus(StatusConstants.PENDING);
     	// 【修改后】
        order.setCreateTime(LocalDateTime.now());
        // 拼装 LocalDateTime：假设 schedule 的 date 是 "2026-10-25"，startTime 是 "14:30"
        dateTimeStr = schedule.getDate() + " " + schedule.getStartTime();
        order.setShowTime(LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")));

        // -------- 步骤 5：保存订单 --------
        order = orderRepository.save(order);

        // -------- 步骤 6：原子锁座（★ 关键改动）--------
        // 逐个用条件 UPDATE 抢座，抢不到就抛异常 → 整个事务回滚
        for (Seat seat : seats) {
            int affected = seatRepository.lockSeat(seat.getId(), order.getId());
            if (affected == 0) {
                // 抢座失败：说明刚才校验通过后，有别的并发请求抢先锁了这个座位
                throw new BusinessException("座位已被选走：" + seat.getRowNum() + "排" + seat.getColNum() + "座");
            }
        }

        log.info("创建订单成功：orderNo={}, userId={}, scheduleId={}, seats={}, totalPrice={}",
                order.getOrderNo(), userId, scheduleId, seatLabels, order.getTotalPrice());
        return order;
    }

    // ==================== 2. 支付 ====================

    /**
     * 【功能】支付订单，生成取票码，座位由 locked 改 sold。
     * 【调用链】
     *   OrderController.pay(id, HttpSession)
     *   → POST /api/orders/{id}/pay
     *   → OrderService.pay(userId, orderId)
     *   → orderRepository.findById / save
     *   → seatRepository.findByOrderId / saveAll
     *
     * 【业务规则】
     *   - 只有 pending 状态可支付
     *   - 只有订单所属用户可支付（越权检查）
     *   - 重复支付抛异常
     *
     * 【异常】
     *   - "订单不存在"
     *   - "无权操作该订单"      userId 与 order.userId 不匹配
     *   - "订单已支付，请勿重复操作"
     *   - "订单状态不允许支付：cancelled"
     *
     * 【取票码格式】8 位字符，中间有横线：ABCD-1234
     *              字符集排除了易混淆的 I/O/0/1。
     */
    @Transactional
    public String pay(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));

        // -------- 越权检查 --------
        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException("无权操作该订单");
        }
        // 重复支付要给明确文案：这句话会直接展示给用户，
        // "状态不允许支付：paid" 既看不懂，也没告诉他该怎么办。
        if (StatusConstants.PAID.equals(order.getStatus())) {
            throw new BusinessException("订单已支付，请勿重复支付");
        }
        if (!StatusConstants.PENDING.equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许支付：" + order.getStatus());
        }

        String mockTradeNo = "WX_" + System.currentTimeMillis();
        log.info("向第三方支付网关发起统一下单，订单号：{}，流水号：{}", order.getOrderNo(), mockTradeNo);

        // 当前没有真实网关：pay() 内部直接完成"支付成功"，与回调共用 markPaid，
        // 保证两条入口的状态流转完全一致。接真实网关时把这一行挪回 handlePayCallback，
        // 其余逻辑不用改。
        markPaid(orderId);
        
        return "http://mock-pay.com?tradeNo=" + mockTradeNo;
    }

    /**
     * 支付回调（由第三方服务器调用）。
     *
     * <p>与 {@link #pay} 共用 {@link #markPaid}：真实网关接入后，pay() 只负责下单拿地址，
     * 落库只在这里发生。两条入口共用同一段状态流转，避免"模拟支付能过、真实回调不行"。
     */
    @Transactional
    public Order handlePayCallback(Long orderId) {
        return markPaid(orderId);
    }

    /**
     * 支付成功的唯一落库点。
     *
     * <p>全程用条件 UPDATE，不"查出实体再 save"：
     * 先 save 会把内存里的旧 status 写回去，覆盖掉刚更新的 paid——这正是
     * "日志显示支付成功、订单却还是 pending"的根因。
     *
     * <p>条件更新影响行数为 0 表示"已经不是 pending"（回调重放、重复支付、订单已取消），
     * 此时返回当前真实状态，不做二次修改，天然幂等。
     */
    @Transactional
    public Order markPaid(Long orderId) {
        if (orderId == null) {
            throw new BusinessException("订单ID不能为空");
        }

        LocalDateTime now = LocalDateTime.now();
        String ticketCode = generateTicketCode();

        int affected = orderRepository.markPaid(orderId, now, ticketCode);
        if (affected == 0) {
            log.warn("【支付回调】条件更新未命中，订单已被处理过或已取消，orderId={}", orderId);
            return orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));
        }

        // 座位 locked → sold：同样走条件 UPDATE，绕过脏检查
        int seats = seatRepository.updateToSoldByOrderId(orderId);

        Order paid = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));

        log.info("订单支付成功：orderId={}, orderNo={}, ticketCode={}, totalPrice={}, 座位数={}",
                orderId, paid.getOrderNo(), ticketCode, paid.getTotalPrice(), seats);
        return paid;
    }

    // ==================== 3. 取消订单 ====================

    /**
     * 【功能】主动取消订单，释放座位。
     * 【调用链】
     *   OrderController.cancel(id, HttpSession)
     *   → POST /api/orders/{id}/cancel
     *   → OrderService.cancel(userId, orderId)
     *   → orderRepository.save（status=cancelled）
     *   → releaseSeats(orderId)（座位回 available）
     *
     * 【业务规则】
     *   - 已支付不可取消（会抛异常）
     *   - 已取消直接返回（幂等）
     *   - 越权检查：userId 必须匹配
     *
     * 【异常】
     *   - "订单不存在"
     *   - "无权操作该订单"
     *   - "已支付订单不可取消"
     */
    @Transactional
    public Order cancel(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));

        if (userId != null && !userId.equals(order.getUserId())) {
            throw new BusinessException("无权操作该订单");
        }

        if (StatusConstants.PAID.equals(order.getStatus())) throw new BusinessException("已支付订单不可取消");
        if (StatusConstants.CANCELLED.equals(order.getStatus())) return order;   // 幂等：已取消直接返回

        // 条件更新替代 save：并发下"用户取消"与"支付回调"只能有一个成功，
        // 影响行数为 0 说明订单刚被支付，直接返回真实状态，不覆盖。
        if (orderRepository.updateToCancelled(orderId) == 0) {
            return orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));
        }
        releaseSeats(orderId);                                      // 座位回 available

        Order cancelled = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));
        log.info("手动取消订单：orderId={}, orderNo={}", orderId, cancelled.getOrderNo());
        return cancelled;
    }

    // ==================== 4. 定时释放超时订单 ====================

    /**
     * 【功能】定时扫描超时未支付订单并自动取消。
     * 【触发机制】@Scheduled(fixedRate = 30_000)
     *            由 Spring 定时任务线程池每 30 秒调用一次。
     *            依赖 MovieTicketingApplication 上的 @EnableScheduling。
     *
     * 【调用链】
     *   Spring 定时调度器 → cancelExpiredOrders()
     *   → orderRepository.findByStatus("pending")
     *   → 逐个判断 createTime + 10分钟 < now
     *   → orderRepository.save（cancelled）+ releaseSeats()
     *
     * 【异常处理】
     *   单个订单时间格式异常会跳过，不影响其它订单处理。
     */
    @Scheduled(fixedRate = 30_000)
    @Transactional
    public void cancelExpiredOrders() {
        List<Order> pendingOrders = orderRepository.findByStatus("pending");
        if (pendingOrders.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        int cancelledCount = 0;

        for (Order order : pendingOrders) {
            if (order.getCreateTime() == null) continue;

            // -------- 解析下单时间（容错）--------
            LocalDateTime created;
            try {
            	created = order.getCreateTime();
            } catch (Exception e) {
                log.warn("订单时间格式异常，跳过：orderId={}", order.getId());
                continue;
            }

            // -------- 超时判断 --------
            if (created.plusMinutes(PAY_TIMEOUT_MINUTES).isBefore(now)) {
                // 条件更新：与用户手动取消、支付回调并发时只有一个能成功，
                // 没抢到说明订单状态已经变了，跳过即可
                if (orderRepository.updateToCancelled(order.getId()) == 0) {
                    continue;
                }
                releaseSeats(order.getId());
                cancelledCount++;
                log.info("超时取消订单：orderId={}, orderNo={}", order.getId(), order.getOrderNo());
            }
        }
        if (cancelledCount > 0) log.info("本轮共自动取消超时订单 {} 个", cancelledCount);
    }

    // ==================== 5. 查询 ====================

    /**
     * 【功能】按用户分页查询订单（我的订单页）。
     * 【调用链】
     *   OrderController.list(HttpSession)               → 全部订单（用户）
     *   PageController.orders(status, page, size)       → 带状态筛选
     *   → OrderService.listPagedByUser(userId, status, page, size)
     *   → orderRepository.findByUserIdOrderByIdDesc
     *     或 findByUserIdAndStatusOrderByIdDesc
     * @param status "all" / "pending" / "paid" / "cancelled"
     */
    public Page<Order> listPagedByUser(Long userId, String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        if (status == null || status.isEmpty() || "all".equals(status)) {
            return orderRepository.findByUserIdOrderByIdDesc(userId, pageable);
        }
        return orderRepository.findByUserIdAndStatusOrderByIdDesc(userId, status, pageable);
    }

    /**
     * 【功能】管理端：分页查询全部订单（带状态筛选）。
     */
    public Page<Order> listPaged(String status, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        if (status == null || status.isEmpty() || "all".equals(status)) {
            return orderRepository.findAllByOrderByIdDesc(pageable);
        }
        return orderRepository.findByStatusOrderByIdDesc(status, pageable);
    }

    /**
     * 【功能】按 ID 查订单。
     * 【调用链】
     *   PageController.orderDetail(id)   → 支付页
     *   OrderController.detail(id)       → REST API
     * @return 不存在返回 null
     */
    public Order getById(Long id) {
        return orderRepository.findById(id).orElse(null);
    }

    // ==================== 私有方法 ====================

    /**
     * 【功能】释放订单占用的座位（locked → available）。
     * 【调用链】
     *   cancel() / cancelExpiredOrders() → releaseSeats(orderId)
     * 【副作用】同时清空 seat.orderId，方便下次被新订单占用。
     */
    private void releaseSeats(Long orderId) {
        // 条件 UPDATE 直接打库：锁座用的也是条件 UPDATE，一级缓存里的 Seat
        // 仍是 available / orderId=null，再改内存实体等于"没有变化"，
        // 脏检查不会发出任何 SQL——这就是"订单取消了、座位还锁着"的原因。
        int released = seatRepository.releaseSeatsByOrderId(orderId);
        if (released > 0) {
            log.debug("释放座位 {} 个：orderId={}", released, orderId);
        }
    }

    /**
     * 【功能】生成取票码，格式 {@code XXXX-XXXX}。
     *
     * <p>字符集去掉了 0/O/1/I/L 等易混字符：取票码是给人工核验的，
     * 看错一个字符就要重新查订单。
     */
    private String generateTicketCode() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 4; i++) {
            sb.append(TICKET_ALPHABET[ThreadLocalRandom.current().nextInt(TICKET_ALPHABET.length)]);
        }
        sb.append('-');
        for (int i = 0; i < 4; i++) {
            sb.append(TICKET_ALPHABET[ThreadLocalRandom.current().nextInt(TICKET_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 【功能】解析座位标签。
     * 【调用链】createOrder() → parseSeatLabel("3排5座") → [3, 5]
     * 【异常】格式不符 → BusinessException("座位格式错误：xxx")
     */
    private int[] parseSeatLabel(String label) {
        Matcher m = SEAT_LABEL.matcher(label == null ? "" : label.trim());
        if (!m.matches()) throw new BusinessException("座位格式错误：" + label);
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /**
     * 【功能】生成订单号。
     * 【格式】"MO" + 毫秒时间戳 + 3 位随机数，例如 "MO1696123456789123"
     * 【⚠️ 碰撞风险】同一毫秒内多次调用可能重复。
     *                生产环境建议用 UUID、雪花 ID 或数据库序列。
     */
    private String generateOrderNo() {
        // 雪花算法生成 Long 类型唯一 ID，转成字符串，前面加个 MO 前缀
        return "MO" + snowflake.nextId();
    }

    /**
     * 【功能】下单参数预校验。
     *
     * <p>captchaTicket（人机校验票据）当前<b>不强制</b>：仓库里还没有签发票据的端点，
     * 网页端根本拿不到票据，强制只会让网页下单 100% 失败（API 客户端随便填个串反而能过），
     * 这本身就是一种"入口不一致"。
     *
     * <p>接入真实人机校验后（签发 ticket → 服务端核销 → 一次性消费），
     * 把下面的判空改回必填即可，前端同时提交 ticket 字段。
     */
    private void validateOrderParams(Long userId, Long scheduleId, List<String> seatLabels, String captchaTicket) {
        if (userId == null) throw new BusinessException("请先登录");
        if (scheduleId == null) throw new BusinessException("场次 ID 不能为空");
        if (seatLabels == null || seatLabels.isEmpty()) throw new BusinessException("请至少选择一个座位");
        if (seatLabels.size() > 6) throw new BusinessException("一次最多选择 6 个座位");

        // 去重校验：同一座位在一次请求里重复出现 → 直接拒绝。
        // 为什么必须在参数校验阶段做：
        //   1) 步骤 3 的预检只查不锁，同一个座位查两次都会是 available，重复项会一路带到步骤 6；
        //   2) 步骤 6 第 2 次 lockSeat 必然影响 0 行（订单已把该座位 locked），于是报成
        //      "座位已被选走"，把"自己重复提交"误报成"被别人抢走"，误导用户和排查方向；
        //   3) 金额按 seatLabels.size() 计算，重复项会让总价虚高，还会多占一个座位名额。
        // 归一化方式与 parseSeatLabel 一致（trim），"3排5座" 与 " 3排5座 " 视为同一座位，
        // 避免用空白绕过；其它内部空白变体本就过不了 ^\d+排\d+座$，不构成绕过面。
        Set<String> distinctLabels = new HashSet<>();
        for (String label : seatLabels) {
            String normalized = label == null ? "" : label.trim();
            if (!distinctLabels.add(normalized)) {
                throw new BusinessException("座位数据异常：请勿重复提交相同座位");
            }
        }
    }
}
