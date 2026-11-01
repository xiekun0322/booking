package com.starscreen.auction.pay;

import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.PayBizType;

/**
 * 支付结果处理器：通道回调 → 业务状态变更的适配点。
 *
 * <p><b>为什么要这层接口</b>：模拟通道和真实网关的区别只在"支付单怎么产生、回调怎么来"，
 * 而"收到成功回调后业务该变成什么"是完全一样的。把这部分抽成接口后，
 * 换真实网关时只需要替换 {@code MockPayService}，业务处理器一行不改。
 *
 * <p><b>实现必须幂等</b>：网关会重复通知（重试机制 + 网络抖动）。
 * 所有状态变更都必须是条件 UPDATE，重复调用影响行数为 0 且不产生副作用。
 */
public interface PaymentResultHandler {

    /** 支持的支付业务类型。 */
    PayBizType bizType();

    /** 支付成功。 */
    void onPaid(AuctionPayOrder order);

    /** 支付失败（此时业务通常保持待支付，允许用户重试）。 */
    void onFailed(AuctionPayOrder order);

    /** 用户取消（语义同失败，但可能需要区分文案与审计记录）。 */
    void onCancelled(AuctionPayOrder order);
}
