package com.starscreen.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.enums.PayStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 支付单（模拟支付通道的本地账本）。
 *
 * <p><b>为什么单独一张表</b>：支付通道只认"支付单号"，业务表只认"业务单号"。
 * 把两者混在一张表里，将来接真实网关时无法替换通道实现（真实网关有自己的
 * 交易号、退款单号、对账文件）。这张表就是将来的"通道适配层"落点：
 * 换网关只改 {@code MockPayService}，业务表结构不动。
 *
 * <p><b>一个业务单可以有多条支付单</b>：用户取消或支付失败后重新付，会产生新的
 * pay_no。业务侧只认"最新一条成功记录"，历史记录保留用于对账与排查。
 */
@Data
@Entity
@Table(name = "auction_pay_order")
public class AuctionPayOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 支付单号（对外主键，回调时用它定位）。 */
    @Column(name = "pay_no", length = 40, nullable = false, unique = true)
    private String payNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "biz_type", length = 20, nullable = false)
    private PayBizType bizType;

    /** 业务单 ID：DEPOSIT 时是 auction_deposit.id，DEAL 时是 auction_deal.id。 */
    @Column(name = "biz_id", nullable = false)
    private Long bizId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private PayStatus status;

    /** 支付渠道标识，模拟实现固定 MOCK。 */
    @Column(length = 20)
    private String channel;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "paid_time")
    private LocalDateTime paidTime;

    /** 失败/取消原因。 */
    @Column(name = "fail_reason", length = 200)
    private String failReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "refund_time")
    private LocalDateTime refundTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
