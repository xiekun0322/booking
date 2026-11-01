package com.starscreen.auction.enums;

/**
 * 拍品状态机。
 *
 * <pre>
 *   DRAFT ──publish──▶ SCHEDULED ──到点──▶ ACTIVE ──到点且有人出价──▶ SETTLED
 *                          │                  │
 *                          │                  └──到点无人出价──▶ UNSOLD
 *                          └──取消──▶ CANCELLED ◀──取消──┘
 *
 *   SETTLED ──中标者到期未付（悔拍）──▶ DEFAULTED
 * </pre>
 *
 * <p><b>状态推进只允许条件 UPDATE</b>（{@code WHERE status = 期望值}），
 * 不读后写：开拍扫描、关拍扫描、用户取消三者会并发抢同一行，
 * 先读再写会把已经推进的状态覆盖回去。影响行数为 0 就是"别人先动了"，按幂等处理。
 */
public enum AuctionStatus {

    /** 草稿：仅落库，不参与任何扫描。 */
    DRAFT,

    /** 已发布，等待开拍。 */
    SCHEDULED,

    /** 竞拍中，唯一可以接受出价的状态。 */
    ACTIVE,

    /** 已成交（有人中标）。 */
    SETTLED,

    /** 已流拍（到点无人出价）。 */
    UNSOLD,

    /** 中标后违约（悔拍），保证金已罚没。 */
    DEFAULTED,

    /** 已取消（卖家或管理员在开拍前撤回）。 */
    CANCELLED;

    /** 是否终态：终态不再参与开拍/关拍扫描，也不能再被取消。 */
    public boolean isTerminal() {
        return this == SETTLED || this == UNSOLD || this == DEFAULTED || this == CANCELLED;
    }

    /** 是否可接受出价。 */
    public boolean canBid() {
        return this == ACTIVE;
    }

    /** 是否可报名（缴纳保证金）。 */
    public boolean canEnroll() {
        return this == SCHEDULED || this == ACTIVE;
    }
}
