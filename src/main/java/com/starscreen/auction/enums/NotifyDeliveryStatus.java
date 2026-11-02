package com.starscreen.auction.enums;

/**
 * 外发通道的投递状态（站内信本身不需要投递，插入即可见）。
 *
 * <pre>
 *   PENDING ──worker 发送成功──▶ SENT
 *      │
 *      ├──失败且可重试──▶ FAILED（退避后回到 PENDING 的语义，仍以 FAILED + next_retry_at 表示）
 *      └──重试超上限──▶ DEAD
 *
 *   SKIPPED：用户没留邮箱，不进入发送队列（不是失败，也不该被重试）
 * </pre>
 *
 * <p><b>为什么 FAILED 与 PENDING 分开</b>：PENDING 是"还没试过"，
 * FAILED 是"试过但失败了"。两者都在 {@code next_retry_at} 到点后被扫描，
 * 但排障时能一眼看出这条到底发过没有。
 */
public enum NotifyDeliveryStatus {

    /** 待发送（尚未尝试）。 */
    PENDING,

    /** 已发送成功。 */
    SENT,

    /** 无收件地址，跳过外发（站内信仍然存在）。 */
    SKIPPED,

    /** 发送失败，等待退避重试。 */
    FAILED,

    /** 重试超上限，放弃自动重试，需人工介入。 */
    DEAD
}
