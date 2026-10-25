package com.starscreen.repository;

import com.starscreen.entity.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 【功能】座位数据访问层。
 *
 * 【调用方】
 *          - SeatService              选座页渲染
 *          - OrderService             下单/支付/取消/超时释放
 *          - DataInitializer          启动时 saveAll
 *
 * 【被调用】
 *          Spring Data JPA → MySQL seat 表
 *
 * 【并发安全】
 *          lockSeat() 用条件 UPDATE（WHERE status='available'）实现原子锁座，
 *          修复了"查 available → 改 locked"之间的超卖窗口。
 */
public interface SeatRepository extends JpaRepository<Seat, Long> {

    /**
     * 【功能】查询某场次的全部座位，按排、座升序。
     * 【SQL】SELECT * FROM seat WHERE schedule_id = ?
     *       ORDER BY row_num ASC, col_num ASC
     * 【调用链】
     *   SeatService.listBySchedule(scheduleId)
     *   → SeatController.listBySchedule(scheduleId)
     *   → GET /api/seats/schedule/{scheduleId}
     *   → seat.html 前端渲染座位图
     */
    List<Seat> findByScheduleIdOrderByRowNumAscColNumAsc(Long scheduleId);

    /**
     * 【功能】按场次 + 排 + 座精确定位一个座位。
     * 【SQL】SELECT * FROM seat WHERE schedule_id = ? AND row_num = ? AND col_num = ?
     * 【调用链】
     *   OrderService.createOrder() 中根据 "3排5座" 解析后查座位
     *   验证 status == "available"，否则抛 BusinessException
     * @return Optional 空表示座位不存在
     */
    Optional<Seat> findByScheduleIdAndRowNumAndColNum(Long scheduleId, Integer rowNum, Integer colNum);

    /**
     * 【功能】查询某订单锁定的所有座位。
     * 【SQL】SELECT * FROM seat WHERE order_id = ?
     * 【调用链】
     *   OrderService.pay()           → 把 locked 座位改为 sold
     *   OrderService.releaseSeats()  → 把 locked 座位改回 available，清空 orderId
     *   OrderService.cancel() / cancelExpiredOrders() 中调用
     */
    List<Seat> findByOrderId(Long orderId);

    /**
     * 【功能】原子锁座：只有 status='available' 时才改成 'locked'。
     * 【SQL】UPDATE seat SET status='locked', order_id=:orderId
     *       WHERE id=:id AND status='available'
     * 【返回】影响行数：
     *           1 → 抢座成功（原状态是 available）
     *           0 → 抢座失败（已被别的并发请求抢先锁走）
     * 【为什么不能用 save()】
     *   save() 会先 select 再 update，中间有窗口期，
     *   两个并发请求可能同时查到 available，都改成 locked。
     *   用条件 UPDATE 一条 SQL 完成，数据库层面保证原子性。
     * 【调用链】
     *   OrderService.createOrder() 步骤 6
     *   → 逐个座位调用 lockSeat
     *   → 影响行数为 0 时抛 BusinessException，整个事务回滚
     */
    @Modifying
    @Query("UPDATE Seat s SET s.status = 'locked', s.orderId = :orderId " +
           "WHERE s.id = :id AND s.status = 'available'")
    int lockSeat(@Param("id") Long id, @Param("orderId") Long orderId);
}