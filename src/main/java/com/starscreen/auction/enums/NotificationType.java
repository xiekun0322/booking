package com.starscreen.auction.enums;

/**
 * 通知类型。
 *
 * <p>只放"用户需要知道、且错过会来找客服"的节点。出价成功、被超越这类高频事件不做：
 * 拍卖过程中一场可能几十上百次，做成通知只会淹没真正重要的那几条。
 */
public enum NotificationType {

    /** 成交：关拍生成成交单，提醒买家在截止时间前付款。 */
    DEAL_WON,

    /** 成交款到账：买家付款成功。 */
    DEAL_PAID,

    /** 保证金已退回（未中标 / 中标付款后 / 流拍）。 */
    DEPOSIT_REFUNDED,

    /** 保证金已罚没（悔拍）。 */
    DEPOSIT_FORFEITED,

    /** 保证金退款多次失败，已转人工处理。 */
    DEPOSIT_REFUND_FAILED
}
