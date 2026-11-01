package com.starscreen.auction.mq;

/**
 * 拍卖消息发布口。
 *
 * <p><b>只有"原样投递"一个方法</b>：所有业务消息都先落发件箱（{@code auction_outbox}），
 * 由 relay 把入表时序列化好的 payload 原样发出。这样做的目的是让"发消息"和
 * "业务提交"共享同一个事务边界——直接调用本接口的场景只剩两类：
 * <ol>
 *   <li>发件箱 relay 重投；</li>
 *   <li>一次性、可丢的旁路通知（不涉及资金）。</li>
 * </ol>
 *
 * <p>不提供 {@code publish(Object)} 这种"传对象、内部再序列化"的方法：
 * 重投时若再走一遍序列化，同一条消息两次投递的内容可能不同，消费端无法判断是否重复。
 */
public interface AuctionEventPublisher {

    /**
     * 按已定死的路由键与已序列化载荷原样投递（发件箱 relay 专用）。
     *
     * @param routingKey 入表时保存的路由键
     * @param payload    入表时保存的 JSON 字符串
     * @throws AuctionEventPublishException broker 不可达或路由失败
     */
    void publishRaw(String routingKey, String payload);
}
