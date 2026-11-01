package com.starscreen.auction.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

/**
 * 拍卖审计日志（拍卖库 {@code auction.auction_audit_log}）。
 *
 * <p>与业务日志的区别：业务日志用来排障，审计用来<b>事后追责</b>——
 * 谁在什么时候把这条拍品改成了什么状态、按的哪条规则。
 * 因此这里<b>只追加，不更新不删除</b>，字段也刻意冗余（actorId 与 actorType 并存），
 * 不允许 join 其它表才能读懂。
 *
 * <p><b>写入必须在业务事务内</b>：状态变了但审计没记，比"状态没变"更难接受。
 */
@Data
@Entity
@Table(name = "auction_audit_log")
public class AuctionAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** BID / TASK / REVIEW / SETTLE / ITEM / DEPOSIT / DEAL。 */
    @Column(name = "biz_type", length = 40, nullable = false)
    private String bizType;

    @Column(name = "biz_id")
    private Long bizId;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    /** USER / AGENT / ADMIN / SYSTEM。 */
    @Column(name = "actor_type", length = 20)
    private String actorType;

    @Column(length = 60)
    private String action;

    @Column(columnDefinition = "longtext")
    private String detail;

    @Column(name = "client_ip", length = 64)
    private String clientIp;

    @Column(name = "create_time")
    private LocalDateTime createTime;
}
