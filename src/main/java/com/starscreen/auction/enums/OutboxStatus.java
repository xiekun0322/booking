package com.starscreen.auction.enums;

/**
 * 发件箱记录状态。
 *
 * <pre>
 *   PENDING ──relay 投递成功──▶ SENT
 *      │
 *      └──重试超上限──▶ DEAD（需人工介入，不自动重投）
 * </pre>
 *
 * <p><b>为什么没有 SENDING</b>：多实例 relay 并发抢占时，"正在发"这个中间态
 * 只能靠租约（{@code locked_until}）表达。多一个 SENDING 状态反而要额外处理
 * "进程崩溃后卡在 SENDING"的回收逻辑——租约到期自然被别人重新捡起，不需要回收。
 */
public enum OutboxStatus {

    /** 待投递：事务已提交，等待 relay 或 afterCommit 快速通道发出去。 */
    PENDING,

    /** 已投递：broker 已确认接收。保留一段时间用于排查，之后分批清理。 */
    SENT,

    /** 投递失败且重试次数耗尽：消息内容仍在表里，可人工重放。 */
    DEAD
}
