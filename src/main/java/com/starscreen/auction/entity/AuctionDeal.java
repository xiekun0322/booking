package com.starscreen.auction.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.DealStatus;

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
 * 成交单（拍卖域自己的交易凭证，不塞进 {@code t_order}）。
 *
 * <p><b>为什么建在拍卖库</b>：关拍与生成成交单在同一个本地事务里完成，
 * 强一致、零延迟、不需要消息。若放进业务库的 {@code t_order}，
 * 两个数据源之间没有本地事务，就退化成"要么异步、要么不一致"。
 *
 * <p><b>金额精度</b>：{@code DECIMAL(18,2)}，与 {@code auction_item} 对齐。
 * 业务库 {@code t_order.total_price} 仍是 10,2（上限约 9999 万），
 * 因此<b>当前不向 t_order 同步任何数据</b>，也就没有溢出风险；
 * 将来若启用 {@code external_order_no} 同步，必须先扩列。
 */
@Data
@Entity
@Table(name = "auction_deal")
public class AuctionDeal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 成交单号（雪花），对外展示与支付单关联用。 */
    @Column(name = "deal_no", length = 40, nullable = false, unique = true)
    private String dealNo;

    /** 拍品 ID：唯一键，一个拍品只能有一张成交单（重拍必须新建 item）。 */
    @Column(name = "item_id", nullable = false, unique = true)
    private Long itemId;

    @Column(name = "item_no", length = 40)
    private String itemNo;

    @Column(name = "buyer_id", nullable = false)
    private Long buyerId;

    @Column(name = "buyer_name", length = 100)
    private String buyerName;

    /** 成交价。 */
    @Column(name = "deal_price", precision = 18, scale = 2, nullable = false)
    private BigDecimal dealPrice;

    /** 中标者缴纳的保证金金额（仅记录，退款金额以 auction_deposit.amount 为准）。 */
    @Column(name = "deposit_amount", precision = 18, scale = 2)
    private BigDecimal depositAmount;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private DealStatus status;

    /** 支付截止时间：到期未付即悔拍。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "pay_deadline", nullable = false)
    private LocalDateTime payDeadline;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "pay_time")
    private LocalDateTime payTime;

    /** 取消原因：PAY_TIMEOUT（悔拍）/ USER_CANCEL / ADMIN_CANCEL。 */
    @Column(name = "cancel_reason", length = 40)
    private String cancelReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    /**
     * 外部订单号（业务库 t_order.order_no）。
     *
     * <p>当前恒为 NULL：拍卖域自成交易域（决策 D6-A），不向 t_order 同步。
     * 字段先留着，将来接 Outbox 同步时直接回填，不需要改表。
     */
    @Column(name = "external_order_no", length = 64)
    private String externalOrderNo;

    @Version
    @Column(nullable = false)
    private Integer version;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
