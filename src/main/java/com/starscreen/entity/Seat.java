package com.starscreen.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Data;

/**
 * 座位实体，映射表 seat。
 *
 * <p>每个座位通过 (schedule_id, row_num, col_num) 唯一定位。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   available → locked   ：下单锁座
 *   locked    → sold     ：支付成功
 *   locked    → available：取消 / 超时释放
 * </pre>
 *
 * <p><b>并发安全</b>：超卖由 {@code SeatRepository.lockSeat} 条件 UPDATE 保证，
 * 详见该方法注释。
 */
@Data
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long scheduleId;

    /** 第几排，从 1 开始。 */
    private Integer rowNum;

    /** 第几座，从 1 开始。 */
    private Integer colNum;

    /** available / locked / sold。 */
    @Column(length = 20)
    private String status;

    /** 关联订单 ID（available 时为 null）。 */
    private Long orderId;
}
