package com.starscreen.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/** 订单实体，映射表 t_order。 */
@Data
@Entity
@Table(name = "t_order")
public class Order {

    /** 主键，自增 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号，唯一，如 "MO1696123456789012" */
    @Column(unique = true, length = 50)
    private String orderNo;

    /** 下单用户 ID（用于越权校验：只能看/操作自己的订单） */
    private Long userId;

    /** 场次 ID */
    private Long scheduleId;

    /** 电影 ID（快照，便于统计） */
    private Long movieId;

    /** 电影名（快照） */
    @Column(length = 100)
    private String movieTitle;

    /** 影院名（快照） */
    @Column(length = 100)
    private String cinemaName;

    /** 影厅名（快照） */
    @Column(length = 50)
    private String hallName;

    /** 场次时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "show_time")
    private LocalDateTime showTime;

    /** 座位标签列表，逗号分隔。 */
    @Column(length = 500)
    private String seats;

    private BigDecimal totalPrice;

    /** pending / paid / cancelled。 */
    @Column(length = 20)
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "pay_time")
    private LocalDateTime payTime;

    /** 取票码。 */
    @Column(length = 20)
    private String ticketCode;
}
