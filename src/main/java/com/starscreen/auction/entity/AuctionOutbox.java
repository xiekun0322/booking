package com.starscreen.auction.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.OutboxStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 发件箱（Outbox）：与业务数据同库、同事务写入，由 relay 异步投递。
 *
 * <p><b>解决什么问题</b>：事务里发 MQ 会出现两种错——事务回滚了但消息已发（幽灵事件），
 * 或者事务提交了但消息没发（丢事件）。前者让下游看到不存在的数据，后者让退款永远不发生。
 * 把消息先写进业务库的同一张表，与业务数据一起提交，就把"发消息"降级成"写一行数据"，
 * 只受本地事务约束；投递失败可以重试，进程崩溃也不丢。
 *
 * <p><b>三条铁律</b>（违反任一，方案即失效）：
 * <ol>
 *   <li><b>insert 必须在业务事务内</b>，不能在 {@code afterCommit} 里写；</li>
 *   <li><b>表必须与业务表同库</b>，跨库就退化成两次独立提交；</li>
 *   <li><b>payload 存已序列化字符串、routing_key 入表时定死</b>，
 *       重投时原样发，不重新序列化、不重算路由。</li>
 * </ol>
 *
 * <p><b>没有 SENDING 状态</b>：并发抢占用 {@code locked_until} 租约表达，
 * 租约到期后记录自动回到可被领取的状态，进程崩溃不需要额外回收逻辑。
 */
@Data
@Entity
@Table(name = "auction_outbox")
public class AuctionOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 聚合类型：AUCTION_ITEM / AUCTION_DEAL / AUCTION_DEPOSIT。 */
    @Column(name = "aggregate_type", length = 40)
    private String aggregateType;

    /** 聚合根 ID（拍品 ID / 成交单 ID）。 */
    @Column(name = "aggregate_id")
    private Long aggregateId;

    /** 事件类型：DEPOSIT_REFUND_TRIGGER / DEPOSIT_FORFEIT / AUCTION_CLOSED。 */
    @Column(name = "event_type", length = 40)
    private String eventType;

    /** 路由键：入表时定死，重投不重算（交换机/路由规则变更不影响存量消息）。 */
    @Column(name = "routing_key", length = 80, nullable = false)
    private String routingKey;

    /** 已序列化的消息体，重投时原样发送。 */
    @Lob
    @Column(nullable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private OutboxStatus status;

    /** 已重试次数。 */
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** 下次可投递时间（退避用）。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    /**
     * 租约到期时间：relay 抢占时写入，期内其它实例不碰这条记录。
     *
     * <p>用它取代 SENDING 状态：状态机少一个分支，崩溃恢复也不需要扫描"卡住"的记录。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "locked_until")
    private LocalDateTime lockedUntil;

    /** 最后一次失败原因（截断到 500 字，避免把堆栈撑爆列）。 */
    @Column(name = "last_error", length = 500)
    private String lastError;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time")
    private LocalDateTime updateTime;
}
