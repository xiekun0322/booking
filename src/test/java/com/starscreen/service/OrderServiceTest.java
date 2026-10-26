package com.starscreen.service;

import com.starscreen.common.BusinessException;
import com.starscreen.entity.Order;
import com.starscreen.entity.Schedule;
import com.starscreen.entity.Seat;
import com.starscreen.repository.OrderRepository;
import com.starscreen.repository.ScheduleRepository;
import com.starscreen.repository.SeatRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 【功能】OrderService 单元测试。
 *         覆盖下单、支付、取消、越权四类核心业务规则。
 *
 * 【本次修复】
 *   createOrder 改用 lockSeat（@Modifying + JPQL）后，
 *   JPQL 的 UPDATE 不经过 Hibernate 一级缓存，
 *   所以同一个事务里查座位会读到旧状态（available）。
 *   修复方式：在断言座位状态前调用 entityManager.clear() 清空一级缓存，
 *   强制从数据库读最新数据。
 *
 * 【运行方式】
 *   mvn test -Dtest=OrderServiceTest
 *
 * 【依赖环境】
 *   @SpringBootTest 会启动完整 Spring 上下文，需要 MySQL 可连接。
 *
 * 【事务回滚】
 *   @Transactional → 每个测试方法结束后自动回滚，不污染数据库。
 */
@SpringBootTest
@Transactional
class OrderServiceTest {

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private ScheduleRepository scheduleRepository;
    @Autowired private SeatRepository seatRepository;
    @Autowired private EntityManager entityManager;   // ★ 用于清一级缓存

    // ==================== 工具方法 ====================

    /**
     * 【功能】从数据库拿一个存在的场次。
     */
    private Schedule firstSchedule() {
        List<Schedule> schedules = scheduleRepository.findAll();
        assertFalse(schedules.isEmpty(), "测试前数据库应有场次数据");
        return schedules.get(0);
    }

    /**
     * 【功能】在某场次里找一个 available 的座位，返回其标签。
     * 【返回】如 "3排5座"
     */
    private String findAvailableSeatLabel(Long scheduleId) {
        List<Seat> seats = seatRepository
                .findByScheduleIdOrderByRowNumAscColNumAsc(scheduleId);
        Seat seat = seats.stream()
                .filter(s -> "available".equals(s.getStatus()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("该场次没有可选座位"));
        return seat.getRowNum() + "排" + seat.getColNum() + "座";
    }

    /**
     * 【功能】从座位标签解析出 [row, col]。
     * 【调用链】各测试方法 → parseRowCol("3排5座") → [3, 5]
     */
    private int[] parseRowCol(String seatLabel) {
        int row = Integer.parseInt(seatLabel.substring(0, seatLabel.indexOf("排")));
        int col = Integer.parseInt(seatLabel.substring(seatLabel.indexOf("排") + 1, seatLabel.indexOf("座")));
        return new int[]{row, col};
    }

    // ==================== 测试用例 ====================

    /**
     * 【测试】正常下单路径。
     * 【修复点】断言座位状态前加 entityManager.clear()
     */
    @Test
    @DisplayName("正常下单：状态为 pending，总价 = 单价 × 座位数")
    void testCreateOrder_Success() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());
        Long testUserId = 999L;

        // 执行被测方法
        Order order = orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));

        // 断言订单
        assertNotNull(order.getId(), "订单应有 ID");
        assertEquals("pending", order.getStatus(), "新订单应为待支付");
        assertEquals(testUserId, order.getUserId(), "订单应绑定用户");
        assertEquals(schedule.getPrice(), order.getTotalPrice(), 0.01, "总价应等于单价");
        assertEquals(seatLabel, order.getSeats(), "座位信息应一致");

        // ★ 清除一级缓存，强制从数据库读最新状态
        entityManager.clear();

        // 断言座位状态：available → locked
        int[] rc = parseRowCol(seatLabel);
        Seat seat = seatRepository
                .findByScheduleIdAndRowNumAndColNum(schedule.getId(), rc[0], rc[1])
                .orElseThrow();
        assertEquals("locked", seat.getStatus(), "下单后座位应被锁定");
        assertEquals(order.getId(), seat.getOrderId(), "座位应关联订单 ID");
    }

    /**
     * 【测试】座位已被占用场景。
     */
    @Test
    @DisplayName("座位已被占用：抛 BusinessException")
    void testCreateOrder_SeatTaken() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());
        Long testUserId = 999L;

        // 第一次下单成功
        orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));

        // 第二次下单同一座位 → 应抛异常
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));
        });
        assertTrue(ex.getMessage().contains("已被选走"), "异常信息应提示座位已占");
    }

    /**
     * 【测试】支付成功路径。
     * 【修复点】断言座位状态前加 entityManager.clear()
     */
    @Test
    @DisplayName("支付成功：状态 paid，生成取票码，座位变 sold")
    void testPay_Success() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());
        Long testUserId = 999L;

        Order order = orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));
        Order paid = orderService.pay(testUserId, order.getId());

        // 断言订单字段
        assertEquals("paid", paid.getStatus(), "支付后状态应为 paid");
        assertNotNull(paid.getTicketCode(), "应生成取票码");
        assertTrue(paid.getTicketCode().matches("[A-Z0-9]{4}-[A-Z0-9]{4}"),
                "取票码格式应为 XXXX-XXXX");
        assertNotNull(paid.getPayTime(), "应有支付时间");

        // ★ 清除一级缓存
        entityManager.clear();

        // 断言座位状态：locked → sold
        int[] rc = parseRowCol(seatLabel);
        Seat seat = seatRepository
                .findByScheduleIdAndRowNumAndColNum(schedule.getId(), rc[0], rc[1])
                .orElseThrow();
        assertEquals("sold", seat.getStatus(), "支付后座位应为 sold");
    }

    /**
     * 【测试】重复支付防护。
     */
    @Test
    @DisplayName("重复支付：抛 BusinessException")
    void testPay_Twice() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());
        Long testUserId = 999L;

        Order order = orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));
        orderService.pay(testUserId, order.getId());

        // 第二次支付 → 应抛异常
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.pay(testUserId, order.getId());
        });
        assertTrue(ex.getMessage().contains("已支付"), "异常信息应提示订单已支付");
    }

    /**
     * 【测试】主动取消订单。
     * 【修复点】断言座位状态前加 entityManager.clear()
     */
    @Test
    @DisplayName("取消订单：状态 cancelled，座位释放")
    void testCancel_Success() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());
        Long testUserId = 999L;

        Order order = orderService.createOrder(testUserId, schedule.getId(), List.of(seatLabel));
        Order cancelled = orderService.cancel(testUserId, order.getId());

        assertEquals("cancelled", cancelled.getStatus(), "取消后状态应为 cancelled");

        // ★ 清除一级缓存
        entityManager.clear();

        // 断言座位状态：locked → available
        int[] rc = parseRowCol(seatLabel);
        Seat seat = seatRepository
                .findByScheduleIdAndRowNumAndColNum(schedule.getId(), rc[0], rc[1])
                .orElseThrow();
        assertEquals("available", seat.getStatus(), "取消后座位应可再次预订");
        assertNull(seat.getOrderId(), "取消后座位的 orderId 应清空");
    }

    /**
     * 【测试】越权防护。
     */
    @Test
    @DisplayName("无权操作：其他用户不能支付他人订单")
    void testPay_Unauthorized() {
        Schedule schedule = firstSchedule();
        String seatLabel = findAvailableSeatLabel(schedule.getId());

        // 用户 100 下单
        Order order = orderService.createOrder(100L, schedule.getId(), List.of(seatLabel));

        // 用户 200 尝试支付 → 应抛异常
        BusinessException ex = assertThrows(BusinessException.class, () -> {
            orderService.pay(200L, order.getId());
        });
        assertTrue(ex.getMessage().contains("无权"), "异常信息应提示无权操作");
    }
}