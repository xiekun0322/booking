package com.starscreen.auction.enums;

/**
 * 模拟支付单状态。
 *
 * <pre>
 *   CREATED ──成功──▶ PAID ──退款──▶ REFUNDED
 *      ├──失败──▶ FAILED
 *      └──取消──▶ CANCELLED
 * </pre>
 *
 * <p>与业务状态分开的原因：支付通道视角只有"这笔钱收没收到"，
 * 而业务侧还有"资格"、"履约"、"退款触发"等语义。
 * 两者混在一张表里，将来换真实网关时无法把"通道状态"和"业务状态"解耦。
 */
public enum PayStatus {

    /** 已下单，等待用户支付。 */
    CREATED,

    /** 支付成功。 */
    PAID,

    /** 支付失败（余额不足、风控拒绝等）。 */
    FAILED,

    /** 用户取消支付，或超时关闭。 */
    CANCELLED,

    /** 已原路退回。 */
    REFUNDED;

    /** 是否还能发起支付。 */
    public boolean payable() {
        return this == CREATED || this == FAILED || this == CANCELLED;
    }
}
