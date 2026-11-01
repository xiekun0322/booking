package com.starscreen.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 出价流水（拍卖库 {@code auction.auction_bid}）。
 *
 * <p><b>被拒绝的出价也要落库</b>（{@code accepted=false} + {@code rejectedReason}）：
 * 拍卖纠纷里最常见的问题是"我明明出过价"，只有成功记录时无法自证。
 * 保留拒绝记录还能统计"用户为什么出价失败"，是调价规则的输入。
 *
 * <p><b>{@code uk_bid_idem} 是幂等键</b>：客户端每次出价生成一个唯一键，
 * 网络重试、用户连点都带同一个键，第二次插入撞键 → 返回首次结果，不会重复加价。
 */
@Data
@Entity
@Table(name = "auction_bid")
public class AuctionBid {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "item_id", nullable = false)
    private Long itemId;

    @Column(name = "bidder_id", nullable = false)
    private Long bidderId;

    @Column(name = "bidder_name", length = 100)
    private String bidderName;

    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal amount;

    /** 客户端生成，全局唯一。 */
    @Column(name = "idempotency_key", length = 64, nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private Boolean accepted;

    /** 被拒原因：BidOutcome 枚举名。 */
    @Column(name = "rejected_reason", length = 40)
    private String rejectedReason;

    /** 存证交易哈希（异步回填）。 */
    @Column(name = "chain_tx_hash", length = 128)
    private String chainTxHash;

    @Column(name = "bid_time", nullable = false)
    private LocalDateTime bidTime;

    @Column(name = "client_ip", length = 64)
    private String clientIp;
}
