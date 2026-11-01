package com.starscreen.auction.mq;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starscreen.auction.entity.AuctionOutbox;
import com.starscreen.auction.enums.OutboxStatus;
import com.starscreen.auction.repository.AuctionOutboxRepository;
import com.starscreen.auction.support.AuctionTxSupport;

import lombok.RequiredArgsConstructor;

/**
 * 发件箱写入器：把"要发一条消息"变成"往业务库插一行数据"。
 *
 * <p><b>调用位置铁律：必须在业务事务内</b>。写在 {@code afterCommit} 里就退化回
 * "消息可能丢"，因为事务提交后进程崩溃，这行记录根本不存在。
 * 当前实现只在业务事务内被调用，{@code afterCommit} 里做的只是"催一把 relay"。
 *
 * <p><b>payload 在入表时序列化</b>：重投原样发，不重新序列化。
 * routingKey 同理，入表时定死。
 *
 * <p><b>为什么要 afterCommit 快速通道</b>：定时 relay 最短也有一个间隔的延迟（默认 3s），
 * 而退款触发这种链路上，3 秒的额外延迟会叠加到用户"点完付款还在等"的体感上。
 * 提交成功后立刻尝试投递一次，失败也不影响正确性——定时任务会兜底重试。
 */
@Component
@RequiredArgsConstructor
public class OutboxWriter {

    private final AuctionOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final OutboxRelay outboxRelay;

    /**
     * 在调用方事务内写入一条待投递消息。
     *
     * @param aggregateType 聚合类型（AUCTION_ITEM / AUCTION_DEAL）
     * @param aggregateId   聚合 ID
     * @param eventType     事件类型
     * @param routingKey    路由键（入表定死）
     * @param payload       消息体对象，此处立即序列化
     * @return 发件箱记录 ID
     */
    public Long write(String aggregateType, Long aggregateId,
                      String eventType, String routingKey, Object payload) {

        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // 序列化失败属于编码错误（字段循环引用、类型不支持），重试多少次都不会好。
            // 直接抛出让业务事务回滚，比"写进去一条注定 DEAD 的记录"更诚实。
            throw new AuctionEventPublishException(
                    "发件箱负载序列化失败 type=" + eventType + " aggregateId=" + aggregateId, e);
        }

        LocalDateTime now = LocalDateTime.now();
        AuctionOutbox box = new AuctionOutbox();
        box.setAggregateType(aggregateType);
        box.setAggregateId(aggregateId);
        box.setEventType(eventType);
        box.setRoutingKey(routingKey);
        box.setPayload(json);
        box.setStatus(OutboxStatus.PENDING);
        box.setRetryCount(0);
        box.setNextRetryAt(now);
        box.setCreateTime(now);
        box.setUpdateTime(now);
        outboxRepository.save(box);

        // IDENTITY 主键策略下 save 会立即 INSERT 并回填 ID，此处可安全引用。
        final Long outboxId = box.getId();
        AuctionTxSupport.afterCommit(() -> outboxRelay.dispatchNow(outboxId));
        return outboxId;
    }
}
