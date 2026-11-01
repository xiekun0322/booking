package com.starscreen.auction.mq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starscreen.auction.config.AuctionRabbitConfig;
import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.service.DepositService;

import lombok.RequiredArgsConstructor;

/**
 * 保证金事件消费者：{@code auction.deposit.queue}。
 *
 * <p><b>职责只有"写标记"</b>，不做任何资金动作：
 * <ul>
 *   <li>{@code DEPOSIT_REFUND_TRIGGER} → 写 {@code refund_trigger_at}，退款由 worker 执行；</li>
 *   <li>{@code DEPOSIT_FORFEIT} → {@code HELD → FORFEITED}。</li>
 * </ul>
 * 把"标记"和"执行"分开，是因为两者的失败模式不同：标记是幂等的数据库操作，
 * 失败重试即可；执行是外部调用，需要退避与人工兜底。混在一起会让重试语义变得含糊。
 *
 * <p><b>为什么消息解析失败要拒收而不是无限重试</b>：载荷损坏是确定性错误，
 * 重试 3 次只是把同一条 ERROR 打三遍。抛 {@link AmqpRejectAndDontRequeueException}
 * 让消息进死信队列，交给人工判断，避免把消费者线程打满。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuctionDepositEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(AuctionDepositEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final DepositService depositService;

    @RabbitListener(queues = AuctionRabbitConfig.DEPOSIT_QUEUE)
    public void onEvent(String payload) {
        AuctionDepositEvent event = parse(payload);
        String type = event.getEventType();
        if (type == null) {
            throw new AmqpRejectAndDontRequeueException("保证金事件缺少 eventType");
        }

        switch (type) {
            case "DEPOSIT_REFUND_TRIGGER" -> depositService.onRefundTrigger(event);
            case "DEPOSIT_FORFEIT" -> depositService.onForfeit(event);
            default -> log.warn("保证金队列收到未知事件类型，忽略 type={} itemId={}",
                    type, event.getItemId());
        }
    }

    private AuctionDepositEvent parse(String payload) {
        if (payload == null || payload.isBlank()) {
            throw new AmqpRejectAndDontRequeueException("保证金事件载荷为空");
        }
        try {
            return objectMapper.readValue(payload, AuctionDepositEvent.class);
        } catch (JsonProcessingException e) {
            throw new AmqpRejectAndDontRequeueException(
                    "保证金事件无法反序列化 payload=" + abbreviate(payload), e);
        }
    }

    private String abbreviate(String payload) {
        return payload.length() <= 200 ? payload : payload.substring(0, 200) + "...";
    }
}
