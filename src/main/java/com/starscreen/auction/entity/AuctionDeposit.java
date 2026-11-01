package com.starscreen.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.DepositStatus;

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
 * 保证金（用户 × 拍品一条）。
 *
 * <p><b>幂等核心是唯一键</b> {@code uk_deposit(user_id, item_id)}：重复报名不会扣两次，
 * 并发报名也只会有一次 insert 成功。这是资金安全的第一道闸，比任何应用层判断都可靠。
 *
 * <p><b>为什么金额要落在这张表上</b>：{@code auction_item.deposit} 是发布时的定价快照，
 * 但用户实际缴纳的金额必须单独记一份——定价规则调整、拍品被改价、甚至运营手工改库，
 * 都不该影响"这个人当时交了多少、该退多少"。退款金额只认 {@code amount}。
 *
 * <p><b>退款触发的落点</b>：{@code refund_trigger_at} 由异步消费者写入（决策一 C / 决策二 B），
 * 退款 worker 只扫 {@code status='HELD' AND refund_trigger_at <= now}——
 * 这样 worker 完全不需要理解拍卖状态机，只管"到点了就退"。
 */
@Data
@Entity
@Table(name = "auction_deposit")
public class AuctionDeposit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    /** 实际缴纳金额（发布时的定价快照，退款只认这个数）。 */
    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private DepositStatus status;

    /** 支付单号（对应 auction_pay_order.pay_no），原路退回时要用。 */
    @Column(name = "pay_no", length = 40)
    private String payNo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "pay_time")
    private LocalDateTime payTime;

    /**
     * 退款触发时间：非空即"该退了"。
     *
     * <p>由消费者写入而不是结算事务直写（决策一 C）。写条件带
     * {@code refund_trigger_at IS NULL}，保证重复消息不会把时间往后推。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "refund_trigger_at")
    private LocalDateTime refundTriggerAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "refund_time")
    private LocalDateTime refundTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "forfeit_time")
    private LocalDateTime forfeitTime;

    /** 退款重试次数（退避与转 REFUND_DEAD 的依据）。 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** 下次重试时间。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
