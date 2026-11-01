package com.starscreen.auction.enums;

/**
 * 保证金状态机（用户 × 拍品一条记录，由 {@code uk_deposit} 唯一键锁死）。
 *
 * <pre>
 *   报名 ──▶ PENDING_PAY ──支付成功──▶ HELD ──┬─未中标 / 中标已付款──▶ REFUNDING ──▶ REFUNDED
 *                │                            ├─悔拍（到期未付）──────▶ FORFEITED
 *                ├─支付失败──▶ PAY_FAILED ────┘（可重新发起支付，回到 PENDING_PAY）
 *                └─用户取消──▶ CLOSED ────────┘（可重新报名，回到 PENDING_PAY）
 *                                             │
 *                                             └─退款多次失败──▶ REFUND_DEAD（人工介入）
 * </pre>
 *
 * <p><b>只有 HELD 才代表"有参拍资格"</b>：{@code BidService} 的出价前置校验只看这一个值。
 * 把资格判断绑在"已扣划成功"上，而不是"报名过"，才能保证悔拍时真有东西可罚没。
 */
public enum DepositStatus {

    /** 已报名，等待支付保证金。 */
    PENDING_PAY,

    /** 已缴纳并冻结，具备参拍资格。 */
    HELD,

    /** 支付失败，可重新发起（同一记录原地复活，不新增行）。 */
    PAY_FAILED,

    /** 用户主动取消报名，可重新报名（同一记录原地复活）。 */
    CLOSED,

    /** 退款中：已抢占退款权（条件 UPDATE 成功），正在调支付通道。 */
    REFUNDING,

    /** 已退款：支付通道确认原路退回。 */
    REFUNDED,

    /** 退款重试耗尽，需人工处理。保留金额与错误信息，不自动再退。 */
    REFUND_DEAD,

    /** 已罚没：悔拍，保证金不退，留痕可审计。 */
    FORFEITED;

    /** 是否具备参拍资格。 */
    public boolean canBid() {
        return this == HELD;
    }

    /** 是否是终态（终态不允许被"重新报名"复活）。 */
    public boolean isTerminal() {
        return this == REFUNDED || this == REFUND_DEAD || this == FORFEITED;
    }
}
