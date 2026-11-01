package com.starscreen.auction.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.starscreen.auction.enums.OutboxStatus;
import com.starscreen.auction.repository.AuctionOutboxRepository;
import com.starscreen.auction.service.DealOverdueSweeper;
import com.starscreen.auction.service.DepositRefundWorker;

import lombok.RequiredArgsConstructor;

/**
 * 资金链路定时任务：退款重试 + 悔拍判定 + 积压巡检。
 *
 * <p><b>为什么不挂在 {@code AuctionLifecycleScheduler} 上</b>：
 * 那个类里的开拍/关拍扫描共用同一个调度线程，而关拍任务在 afterCommit 里同步发 MQ，
 * broker 不可用时会阻塞到连接超时。若把退款/悔拍也塞进同一个线程，
 * 三个任务会互相饿死——表现为"拍品不开拍、钱也不退"，但日志里只有一行发送失败。
 *
 * <p><b>这里仍然是同一个 {@code @Scheduled} 线程池</b>（Spring 默认单线程，
 * 已在 application.properties 把 {@code spring.task.scheduling.pool.size} 调到 4）。
 * 任务之间靠线程池隔离，不再互相阻塞。若将来某个任务明显变慢，
 * 再单独给它一个 {@code TaskScheduler}。
 *
 * <p><b>三项任务的分工</b>：
 * <ul>
 *   <li>退款：扫 {@code HELD + refund_trigger_at <= now}，原路退回；</li>
 *   <li>悔拍：扫 {@code PENDING_PAYMENT + pay_deadline < now}，判 CANCELLED 并罚没；</li>
 *   <li>积压巡检：只打日志/告警，不改数据。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuctionFundsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionFundsScheduler.class);

    private final DepositRefundWorker refundWorker;
    private final DealOverdueSweeper overdueSweeper;
    private final AuctionOutboxRepository outboxRepository;

    /** 退款重试扫描。 */
    @Scheduled(fixedDelayString = "${app.auction.deal.refund-sweep-interval-ms:15000}")
    public void sweepRefund() {
        try {
            refundWorker.sweepRefund();
        } catch (RuntimeException e) {
            log.error("退款扫描失败，下一轮重试", e);
        }
    }

    /** 悔拍判定扫描。 */
    @Scheduled(fixedDelayString = "${app.auction.deal.overdue-sweep-interval-ms:10000}")
    public void sweepOverdue() {
        try {
            overdueSweeper.sweepOverdue();
        } catch (RuntimeException e) {
            log.error("悔拍扫描失败，下一轮重试", e);
        }
    }

    /**
     * 积压巡检（每 5 分钟）：只观测，不处理。
     *
     * <p>为什么单独一个任务而不是顺带打日志：顺带打日志会被"没有积压"的正常路径淹没，
     * 而积压恰恰是资金链路唯一需要提前发现的故障形态。
     */
    @Scheduled(fixedDelay = 300_000L)
    public void reportBacklog() {
        try {
            long pending = outboxRepository.countByStatus(OutboxStatus.PENDING);
            long dead = outboxRepository.countByStatus(OutboxStatus.DEAD);
            if (dead > 0) {
                log.error("【资金链路告警】发件箱 DEAD 记录 {} 条，需人工重放", dead);
            }
            if (pending > 200) {
                log.error("【资金链路告警】发件箱积压 PENDING {} 条，检查 broker 与消费者", pending);
            } else {
                log.info("【资金链路】发件箱待投递 {} 条，DEAD {} 条", pending, dead);
            }
        } catch (RuntimeException e) {
            log.warn("积压巡检失败", e);
        }
    }
}
