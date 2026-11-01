package com.starscreen.auction.mq;

/**
 * 拍卖消息投递失败。
 *
 * <p>单独一个异常类型，是为了让调用方能区分"业务拒绝了"和"消息没发出去"：
 * 前者应当把错误提示给用户，后者应当让事务回滚或交给发件箱重试。
 * 全部抛 {@code RuntimeException} 会让这两种情况在 catch 里混成一团。
 */
public class AuctionEventPublishException extends RuntimeException {

    public AuctionEventPublishException(String message) {
        super(message);
    }

    public AuctionEventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
