package com.starscreen.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.starscreen.auction.enums.AuctionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

/**
 * 拍品（拍卖库 {@code auction.auction_item}）。
 *
 * <p><b>金额一律 DECIMAL(18,2)</b>：与 {@code auction_deal.deal_price} 同精度，
 * 不与票务库 {@code t_order.total_price}(10,2) 混用——两域金额不互通，不需要对齐。
 *
 * <p><b>{@code currentPrice} 是并发控制的核心</b>：出价不是"读-改-写"，
 * 而是带 {@code WHERE current_price < :amount} 的条件 UPDATE。
 * 两个人同时出价时只有一个人能影响 1 行，另一个拿到 0 行后重试或被告知最低价。
 *
 * <p><b>{@code version}</b> 只服务于 JPA 的乐观锁（管理端编辑拍品信息时用），
 * 出价链路<b>不依赖</b>它——条件 UPDATE 本身已经保证了正确性。
 */
@Data
@Entity
@Table(name = "auction_item")
public class AuctionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 业务编号（对外展示，不暴露自增 ID）。 */
    @Column(name = "item_no", length = 40, nullable = false, unique = true)
    private String itemNo;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    /** MOVIE_PROP / POSTER / TICKET_PACKAGE ... */
    @Column(length = 40)
    private String category;

    @Column(name = "seller_id")
    private Long sellerId;

    @Column(name = "seller_name", length = 100)
    private String sellerName;

    @Column(name = "start_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal startPrice;

    /** 当前价，出价用条件 UPDATE 改它。 */
    @Column(name = "current_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal currentPrice;

    /** 一口价；NULL 表示不设。 */
    @Column(name = "buyout_price", precision = 18, scale = 2)
    private BigDecimal buyoutPrice;

    /** 最小加价幅度。 */
    @Column(name = "bid_increment", precision = 18, scale = 2, nullable = false)
    private BigDecimal bidIncrement;

    /**
     * 应缴保证金（发布时按 clamp(起拍价×比例, 下限, 上限) 固化）。
     *
     * <p>报名、扣划、退款全部只读这个快照，<b>永不重算</b>：
     * 调比例只影响新发布的拍品，存量拍的应缴/应退金额不会漂移。
     */
    @Column(precision = 18, scale = 2, nullable = false)
    private BigDecimal deposit;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private AuctionStatus status;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** 0 表示不自动延时；>0 表示"剩余不足该秒数时出价，顺延该秒数"。 */
    @Column(name = "auto_extend_seconds", nullable = false)
    private Integer autoExtendSeconds;

    /** 已顺延次数，受 {@code app.auction.max-extend-times} 限制，防止永不结束。 */
    @Column(name = "extend_times", nullable = false)
    private Integer extendTimes;

    @Column(name = "leader_id")
    private Long leaderId;

    @Column(name = "leader_name", length = 100)
    private String leaderName;

    /** 成交价（结算时快照 currentPrice）。 */
    @Column(name = "final_price", precision = 18, scale = 2)
    private BigDecimal finalPrice;

    @Column(name = "settle_time")
    private LocalDateTime settleTime;

    @Version
    @Column(nullable = false)
    private Integer version;

    @Column(name = "create_time")
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
