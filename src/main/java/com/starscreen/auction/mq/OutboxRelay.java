package com.starscreen.auction.mq;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.entity.AuctionOutbox;
import com.starscreen.auction.repository.AuctionOutboxRepository;

/**
 * 发件箱投递器：把 PENDING 记录送到 broker，失败退避重试，超上限转 DEAD。
 *
 * <p><b>为什么状态流转要拆成多个小事务</b>：
 * <ol>
 *   <li>抢占租约必须<b>先提交</b>，否则其它实例看不到租约，会重复投递；</li>
 *   <li>发 MQ 必须在事务<b>之外</b>，否则网络阻塞会把数据库连接一起占住；</li>
 *   <li>标记结果也必须<b>独立提交</b>，否则发送成功但标记失败会导致重复投递
 *       （重复由消费端幂等兜底，但能少一次是一次）。</li>
 * </ol>
 *
 * <p><b>没有 SENDING 状态</b>：租约 {@code locked_until} 到期后记录自动回到可领取状态。
 * 进程在"已发未标"的窗口崩溃时，消息会重投一次——这是可接受的，
 * 因为消费端全部用条件 UPDATE 保证幂等。
 *
 * <p><b>与定时任务的关系</b>：本类的 {@code relay()} 是兜底通道，
 * {@code OutboxWriter} 在 afterCommit 里调 {@code dispatchNow()} 是快速通道。
 * 两条路都走同一个 {@link #dispatch}，行为完全一致。
 */
@Component
@ConditionalOnProperty(prefix = "app.auction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** 退避上限（秒），避免重试间隔无限增长。 */
    private static final long MAX_BACKOFF_SECONDS = 300L;

    private final AuctionOutboxRepository outboxRepository;
    private final AuctionEventPublisher eventPublisher;
    private final AuctionProperties properties;
    private final TransactionTemplate tx;

    public OutboxRelay(AuctionOutboxRepository outboxRepository,
                       AuctionEventPublisher eventPublisher,
                       AuctionProperties properties,
                       @Qualifier("auctionTransactionManager") PlatformTransactionManager auctionTransactionManager) {
        this.outboxRepository = outboxRepository;
        this.eventPublisher = eventPublisher;
        this.properties = properties;
        this.tx = new TransactionTemplate(auctionTransactionManager);
        // 【必须是 REQUIRES_NEW】本类的两个入口都可能处在"外层事务已提交、但事务资源尚未清理"的窗口里：
        //   * dispatchNow 由 afterCommit 回调触发；
        //   * relay 由定时线程触发（本身无事务）。
        // 若用默认的 REQUIRED，afterCommit 阶段 Spring 仍认为当前线程有事务，于是"加入"这个
        // 已经提交的上下文，不再新开事务 —— 批量 UPDATE 因此报
        // "no transaction is in progress"，快速投递必然失败一次，只能靠定时兜底。
        // REQUIRES_NEW 会挂起旧资源、另起一个干净事务，两处入口都成立。
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 兜底扫描：定时把积压的 PENDING 记录投出去。 */
    @Scheduled(fixedDelayString = "${app.auction.outbox.relay-interval-ms:3000}")
    public void relay() {
        try {
            AuctionProperties.Outbox cfg = properties.getOutbox();
            List<AuctionOutbox> batch = outboxRepository.findDispatchable(LocalDateTime.now(), cfg.getBatchSize());
            for (AuctionOutbox box : batch) {
                dispatch(box);
            }
        } catch (RuntimeException e) {
            log.error("发件箱扫描失败，下一轮重试", e);
        }
    }

    /**
     * 快速通道：afterCommit 里立刻尝试投递一次。
     *
     * <p>失败<b>不抛</b>：调用点都在事务提交之后，此时抛异常会污染调用方语义
     * （业务已成功却返回 500）。失败留给定时 relay 重试。
     */
    public void dispatchNow(Long outboxId) {
        try {
            outboxRepository.findById(outboxId).ifPresent(this::dispatch);
        } catch (RuntimeException e) {
            log.warn("发件箱快速投递失败，等待定时重试 outboxId={}", outboxId, e);
        }
    }

    /** 清理已投递的历史记录（每天 03:30，分批删除避免长事务）。 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void purgeSent() {
        try {
            int days = properties.getOutbox().getSentRetentionDays();
            int deleted = outboxRepository.purgeSentBefore(
                    LocalDateTime.now().minusDays(days), properties.getOutbox().getPurgeBatch());
            if (deleted > 0) {
                log.info("【发件箱】清理已投递记录 {} 条（保留 {} 天）", deleted, days);
            }
        } catch (RuntimeException e) {
            log.error("发件箱清理失败，明天再试", e);
        }
    }

    // ==================== 内部 ====================

    private void dispatch(AuctionOutbox box) {
        AuctionProperties.Outbox cfg = properties.getOutbox();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime leaseUntil = now.plusSeconds(cfg.getLeaseSeconds());

        // 1. 抢占租约（独立事务先提交，否则多实例会同时发）
        Boolean claimed = tx.execute(s -> outboxRepository.claim(box.getId(), leaseUntil, now) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            // 被别的实例抢走了，本轮静默跳过（正常竞争，不是错误）
            return;
        }

        // 2. 投递（事务外，避免网络 IO 占用数据库连接）
        try {
            eventPublisher.publishRaw(box.getRoutingKey(), box.getPayload());
        } catch (RuntimeException e) {
            onFailure(box, e, cfg);
            return;
        }

        // 3. 标记成功（独立事务）
        try {
            tx.execute(s -> outboxRepository.markSent(box.getId(), LocalDateTime.now()));
            log.debug("发件箱投递成功 outboxId={} type={}", box.getId(), box.getEventType());
        } catch (RuntimeException e) {
            // 已经发出去了，标记失败只会导致重复投递，由消费端幂等兜底
            log.error("发件箱已投递但标记失败，可能重复投递 outboxId={}", box.getId(), e);
        }
    }

    private void onFailure(AuctionOutbox box, RuntimeException error, AuctionProperties.Outbox cfg) {
        int current = box.getRetryCount() == null ? 0 : box.getRetryCount();
        int next = current + 1;
        String message = truncate(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());

        if (next > cfg.getMaxRetry()) {
            tx.execute(s -> outboxRepository.markDead(box.getId(), message, LocalDateTime.now()));
            log.error("【发件箱】重试耗尽转 DEAD，需人工重放 outboxId={} type={} routingKey={}",
                    box.getId(), box.getEventType(), box.getRoutingKey(), error);
            return;
        }

        long backoff = Math.min(5L * (1L << Math.min(next, 6)), MAX_BACKOFF_SECONDS);
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoff);
        tx.execute(s -> outboxRepository.markRetry(box.getId(), next, nextRetryAt, message, LocalDateTime.now()));
        log.warn("发件箱投递失败，{} 秒后第 {} 次重试 outboxId={} err={}",
                backoff, next, box.getId(), message);
    }

    private String truncate(String text) {
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
