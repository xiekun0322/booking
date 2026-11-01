package com.starscreen.auction.enums;

/**
 * 成交单状态（拍卖库内独立交易域，见决策 D6-A）。
 *
 * <pre>
 *   关拍成交 ──▶ PENDING_PAYMENT ──支付成功──▶ PAID ──▶ COMPLETED
 *                      │
 *                      ├─ pay_deadline 到期未付（悔拍）──▶ CANCELLED
 *                      └─ 取消 ────────────────────────▶ CANCELLED
 * </pre>
 *
 * <p><b>与拍品状态的关系</b>：{@code auction_item} 表达"拍卖结果"（成交/流拍），
 * {@code auction_deal} 表达"交易履约"（待付/已付/已取消）。两者不做联动回写——
 * item 一旦 SETTLED 就不再变，deal 后来因悔拍变 CANCELLED 也不回头改 item。
 * 前端展示规则据此确定：以 item 判断"这场拍卖有没有成交"，以 deal 判断"钱收到没有"。
 */
public enum DealStatus {

    /** 待支付：关拍即生成，等待买家付成交款。 */
    PENDING_PAYMENT,

    /** 已支付：成交款到账（此时才触发中标者保证金退款）。 */
    PAID,

    /** 已完成：履约结束（发货/交割完成），当前未自动化，保留扩展。 */
    COMPLETED,

    /** 已取消：悔拍或人工取消，保证金按规则罚没。 */
    CANCELLED;

    /** 是否还允许支付。 */
    public boolean payable() {
        return this == PENDING_PAYMENT;
    }
}
