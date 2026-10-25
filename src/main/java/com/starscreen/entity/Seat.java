package com.starscreen.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 【功能】座位实体，映射数据库表 seat。
 *         每个座位属于一个场次，通过 (schedule_id, row_num, col_num) 唯一定位。
 *
 * 【表结构】
 *          seat(id, schedule_id, row_num, col_num, status, order_id)
 *
 * 【座位状态机】
 *          available → locked   ：用户下单锁座
 *          locked    → sold     ：用户支付成功
 *          locked    → available：取消订单 / 超时释放
 *
 * 【调用方】
 *          - SeatRepository           持久化
 *          - SeatService              选座页展示
 *          - OrderService             下单锁座 / 支付售出 / 取消释放
 *          - DataInitializer          启动时批量生成
 *
 * 【被调用】
 *          无（叶子实体）
 *
 * 【⚠️ 并发隐患】
 *          OrderService.createOrder() 的"检查 status=available → 更新为 locked"
 *          之间没有锁，高并发下可能把同一座位卖给两人（超卖）。
 *          修复：PESSIMISTIC_WRITE 锁、乐观锁 @Version、
 *                或数据库条件更新 UPDATE ... WHERE status='available'。
 */
@Data
@Entity
@Table(name = "seat")
public class Seat {

    /** 主键，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属场次 ID */
    private Long scheduleId;

    /** 第几排（从 1 开始） */
    private Integer rowNum;

    /** 第几座（从 1 开始） */
    private Integer colNum;

    /** 状态：available / locked / sold */
    @Column(length = 20)
    private String status;

    /** 关联的订单 ID（available 状态下为 null） */
    private Long orderId;
}
