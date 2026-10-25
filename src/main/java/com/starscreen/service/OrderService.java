package com.starscreen.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.starscreen.common.BusinessException;
import com.starscreen.common.RoleConstants;
import com.starscreen.common.StatusConstants;
import com.starscreen.config.RabbitMQConfig;
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

    /** 座位标签正则：必须匹配 "3排5座" 格式 */
    // 限制排号和座号最多两位数，即最多 99 排 99 座（符合真实影院情况）
    private static final Pattern SEAT_LABEL = Pattern.compile("^(\\d{1,2})排(\\d{1,2})座$");

    /** 单笔订单最多座位数（与 CreateOrderRequest 的 @Size(max=6) 呼应） */
    private static final int MAX_SEATS_PER_ORDER = 6;

    /** 订单号 / 取票码生成用的随机数 */
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();

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
     *   - "场次不存在"
     *   - "座位不存在：xxx"   座位标签格式对但数据库没这个座位
     *   - "座位已被选走：xxx" 座位状态不是 available（预检）
     *   - "座位已被选走：xxx" 条件 UPDATE 影响行数为 0（并发抢座失败）
     *   - "座位格式错误：xxx" 标签不符合 ^\d+排\d+座$
     */
    @Transactional
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
        String dateTimeStr = schedule.getDate() + " " + schedule.getStartTime();
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

        // 锁座成功后，发送延迟消息！
        // 假设你在配置类里定义了一个延迟队列叫 "order.delay.queue"
        // 延迟时间设置为 10 分钟 (600000 毫秒)
        // 【修复】发送到普通交换机，队列里我们已经配置了 10 分钟 TTL，时间一到自动变死信
        rabbitTemplate.convertAndSend(
                com.starscreen.config.RabbitMQConfig.ORDER_EXCHANGE,
                com.starscreen.config.RabbitMQConfig.ORDER_ROUTING_KEY,
                order.getId()
        );
        
        // 【新增】发送延迟消息。消息内容就是订单ID
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_EXCHANGE,
                RabbitMQConfig.ORDER_ROUTING_KEY,
                order.getId() // 把订单ID传过去
        );
        log.info("已发送订单延迟检查消息，orderId={}", order.getId());

        return order;
    }

    // ==================== 2. 支付 ====================

    /**
     * 发起支付（预下单）
     */
    @Transactional
    public String createPayTransaction(Long userId, Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));

        if (!userId.equals(order.getUserId())) {
            throw new BusinessException("无权操作该订单");
        }
        if (!StatusConstants.PENDING.equals(order.getStatus())) {
            throw new BusinessException("订单状态不允许支付：" + order.getStatus());
        }

        String mockTradeNo = "WX_" + System.currentTimeMillis();
        log.info("向第三方支付网关发起统一下单，订单号：{}，流水号：{}", order.getOrderNo(), mockTradeNo);
        
        return "http://mock-pay.com?tradeNo=" + mockTradeNo;
    }

    /**
     * 支付回调（由第三方服务器调用）
     */
    @Transactional
    public void handlePayCallback(Long orderId) {
        // 【新增这一行】防止传入的 orderId 为 null，同时消除红波浪线
        if (orderId == null) {
            throw new BusinessException("订单ID不能为空");
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new BusinessException(MSG_ORDER_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now();
        
        // 核心：原子更新
        // 只传两个参数：订单ID和当前时间
        int affected = orderRepository.updateToPaid(orderId, now);

        if (affected == 0) {
            log.warn("【支付回调】处理失败，订单已被处理过或已取消，orderId={}", orderId);
            return;
        }

        order.setPayTime(now);
        orderRepository.save(order);

        // 一条 SQL 批量更新座位状态，直接干掉循环
        int seatCount = seatRepository.updateToSoldByOrderId(orderId);
        log.info("【支付回调】订单 {} 关联的 {} 个座位已批量更新为 sold", orderId, seatCount);

        log.info("【支付回调】订单支付成功，已生成取票码：{}", order.getTicketCode());
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

        // 只要 userId 是 null，或者 userId 跟订单里的不对，统统赶走！
        if (userId == null || !userId.equals(order.getUserId())) {
            throw new BusinessException("无权操作该订单");
        }

        if (StatusConstants.PAID.equals(order.getStatus())) throw new BusinessException("已支付订单不可取消");
        if (StatusConstants.CANCELLED.equals(order.getStatus())) return order;   // 幂等：已取消直接返回

        order.setStatus(StatusConstants.CANCELLED);
        orderRepository.save(order);
        releaseSeats(order.getId());                                // 座位回 available

        log.info("手动取消订单：orderId={}, orderNo={}", orderId, order.getOrderNo());
        return order;
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
     *   
     *   详情见：service/OrderTimeoutConsumer.java，使用 MQ 延迟队列处理超时订单，避免定时任务扫描压力。
     */

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
     * 【功能】释放订单占用的座位（locked → available）
     * 【调用链】
     *   cancel() / cancelExpiredOrders() → releaseSeats(orderId)
     * 【副作用】同时清空 seat.orderId，方便下次被新订单占用。
     */
    private void releaseSeats(Long orderId) {
        // 一条 SQL 批量释放
        int count = seatRepository.releaseSeatsByOrderId(orderId);
        log.info("释放座位成功，订单 {} 共释放 {} 个座位", orderId, count);
    }

    /**
     * 【功能】解析座位标签。
     * 【调用链】createOrder() → parseSeatLabel("3排5座") → [3, 5]
     * 【异常】格式不符 → BusinessException("座位格式错误：xxx")
     */
    private int[] parseSeatLabel(String label) {
        Matcher m = SEAT_LABEL.matcher(label == null ? "" : label.trim());
        if (!m.matches()) {
            throw new BusinessException("座位格式错误：" + label);
        }
        
        try {
            // 虽然加了限制，但为了绝对的健壮性，加上 try-catch 防止未知的溢出
            return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
        } catch (NumberFormatException e) {
            // 即使正则被绕过，也会被这里拦住，返回友好的业务提示，而不是系统报错
            throw new BusinessException("座位数字越界：" + label);
        }
    }

    /**
     * 【功能】生成订单号。
     * 【格式】"MO" + 毫秒时间戳 + 3 位随机数，例如 "MO1696123456789123"
     * 【⚠️ 碰撞风险】同一毫秒内多次调用可能重复。
     *                生产环境建议用 UUID、雪花 ID 或数据库序列。
     */
    /**
     * 生成订单号（改用雪花算法防碰撞）
     */
    private String generateOrderNo() {
        // 雪花算法生成 Long 类型唯一 ID，转成字符串，前面加个 MO 前缀
        return "MO" + snowflake.nextId();
    }

    private void validateOrderParams(Long userId, Long scheduleId, List<String> seatLabels, String captchaTicket) {
        if (userId == null) throw new BusinessException("请先登录");
        if (scheduleId == null) throw new BusinessException("场次 ID 不能为空");
        if (seatLabels == null || seatLabels.isEmpty()) throw new BusinessException("请至少选择一个座位");
        if (seatLabels.size() > 6) throw new BusinessException("一次最多选择 6 个座位");
        
        // 新增：校验验证码票据是否为空
        if (captchaTicket == null || captchaTicket.trim().isEmpty()) {
            throw new BusinessException("验证码已失效，请重新获取");
        }
    }
}
