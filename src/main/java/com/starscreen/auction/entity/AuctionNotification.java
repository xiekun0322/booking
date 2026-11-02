package com.starscreen.auction.entity;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.NotificationType;
import com.starscreen.auction.enums.NotifyDeliveryStatus;

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
 * 站内信 + 外发投递记录（一条记录同时承载两者）。
 *
 * <p><b>为什么不拆成"通知表 + 投递任务表"</b>：站内信插入即送达，
 * 只有一个通道需要异步投递。拆两张表只会多一次 join、多一份一致性负担，
 * 而收益是零——同一套思路已经在保证金退款上用过（不建独立退款任务表）。
 *
 * <p><b>写在业务事务内</b>：成交、支付、退款这些动作成功了，通知就必须存在。
 * 若放到 afterCommit 里写，进程崩溃就丢——用户会觉得"钱退了但没通知"，
 * 而这恰恰是最需要留痕的场景。
 *
 * <p><b>外发为什么异步</b>：邮件网关的 RT 不可控（秒级到超时），
 * 让它进业务事务等于把成交/退款的成功率绑在第三方上。
 * 这里只落一条待投递记录，由 {@code NotificationEmailWorker} 后台发送并重试。
 */
@Data
@Entity
@Table(name = "auction_notification")
public class AuctionNotification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 收件人（裸 ID，不建跨库外键）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 40, nullable = false)
    private NotificationType type;

    @Column(name = "title", length = 120, nullable = false)
    private String title;

    @Column(name = "content", length = 500, nullable = false)
    private String content;

    /** 关联业务类型：DEAL / DEPOSIT / ITEM，便于按业务反查。 */
    @Column(name = "biz_type", length = 20)
    private String bizType;

    @Column(name = "biz_id")
    private Long bizId;

    /** 是否已读：站内信未读数直接由它统计。 */
    @Column(name = "read_flag", nullable = false)
    private Boolean readFlag;

    /** 外发收件地址（当前为邮箱）。为空时投递直接置 SKIPPED。 */
    @Column(name = "email", length = 120)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", length = 20, nullable = false)
    private NotifyDeliveryStatus deliveryStatus;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    /** 下次可发送时间：退避用，为空表示立即可发。 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    @Column(name = "update_time", nullable = false)
    private LocalDateTime updateTime;
}
