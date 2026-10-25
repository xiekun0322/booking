package com.starscreen.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Data
@Entity
@Table(name = "t_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, length = 50)
    private String orderNo;

    private Long userId;
    private Long scheduleId;
    private Long movieId;

    @Column(length = 100)
    private String movieTitle;

    @Column(length = 100)
    private String cinemaName;

    @Column(length = 50)
    private String hallName;

    /** 场次时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "show_time")
    private LocalDateTime showTime;

    /** 座位列表 */
    @Column(length = 500)
    private String seats;

    /** 总价 */
    private BigDecimal totalPrice;

    /** 状态 */
    @Column(length = 20)
    private String status;

    /** 下单时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    /** 支付时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "pay_time")
    private LocalDateTime payTime;

    /** 取票码 */
    @Column(length = 20)
    private String ticketCode;
}
