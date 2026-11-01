package com.starscreen.auction.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import com.starscreen.auction.config.AuctionRabbitConfig;

import lombok.RequiredArgsConstructor;

/**
 * RabbitMQ 实现：把发件箱里的一行数据原样发出去。
 *
 * <p><b>不做任何转换</b>：payload 是入表时序列化好的 JSON，routingKey 是入表时定死的值。
 * 这里唯一的动作是"发出去，失败就抛"，把重试决策留给发件箱 relay——
 * 如果这里自己重试，relay 也重试，两者叠加会让同一条消息被放大成 N×M 次。
 */
@Component
@RequiredArgsConstructor
public class RabbitAuctionEventPublisher implements AuctionEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitAuctionEventPublisher.class);

    private final RabbitTemplate rabbitTemplate;

    @Override
    public void publishRaw(String routingKey, String payload) {
        if (routingKey == null || routingKey.isBlank()) {
            throw new AuctionEventPublishException("发件箱记录缺少 routing_key，拒绝投递");
        }
        try {
            rabbitTemplate.convertAndSend(AuctionRabbitConfig.AUCTION_EXCHANGE, routingKey, payload);
        } catch (RuntimeException e) {
            throw new AuctionEventPublishException("发件箱消息投递失败 routingKey=" + routingKey, e);
        }
        log.debug("发件箱消息已投递 routingKey={}", routingKey);
    }
}
