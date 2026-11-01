package com.starscreen.auction.enums;

/**
 * 模拟支付单所属业务类型。
 *
 * <p>一个支付单只服务一个业务对象，回调时据此分发到不同的处理器：
 * 保证金回调改 {@code auction_deposit}，成交款回调改 {@code auction_deal}。
 * 不做"通用支付 + 业务判断写在回调里"的设计——那样回调一改就可能影响另一条业务线。
 */
public enum PayBizType {

    /** 参拍保证金。 */
    DEPOSIT,

    /** 成交款。 */
    DEAL
}
