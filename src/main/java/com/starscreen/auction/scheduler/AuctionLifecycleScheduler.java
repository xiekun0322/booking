package com.starscreen.auction.scheduler;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.service.AuctionService;
import com.starscreen.auction.service.BidRateLimiter;

import lombok.RequiredArgsConstructor;

/**
 * 拍卖生命周期定时任务：开拍扫描 + 积压巡检。
 *
 * <p><b>为什么必须有积压巡检</b>：开拍扫描成功时只打一行"已开拍 N 个"，
 * 没有拍品到点时什么都不打。于是"任务没在跑"和"确实没到点"在日志上完全一样——
 * 曾经出现过调度线程被关拍任务的同步 MQ 发送卡死几十分钟，期间没有任何 ERROR，
 * 直到用户投诉"拍品一直不开拍"才被发现。
 *
 * <p>这里每分钟把"已到开始时间但仍排期中"的条数打出来，>0 且持续增长就是调度出问题的信号。
 * 指标比日志更可靠，但这一步至少让日志能自证。
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.auction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AuctionLifecycleScheduler {

    private static final Logger log = LoggerFactory.getLogger(AuctionLifecycleScheduler.class);

    private final AuctionService auctionService;
    private final AuctionItemRepository itemRepository;
    private final BidRateLimiter rateLimiter;

    /** 开拍扫描：每 5 秒一次（拍卖开始时间精确到秒，间隔太长用户会觉得"到点没开始"）。 */
    @Scheduled(fixedDelayString = "${app.auction.start-interval-ms:5000}")
    public void startDueAuctions() {
        try {
            auctionService.startDueAuctions();
        } catch (RuntimeException e) {
            // 吞掉异常是为了让下一轮继续；但必须打完整堆栈，否则只剩"没开拍"这一个现象
            log.error("开拍扫描失败，下一轮重试", e);
        }
    }

    /** 积压巡检 + 限流窗口清理（每分钟）。 */
    @Scheduled(fixedDelay = 60_000L)
    public void inspectBacklog() {
        try {
            long backlog = itemRepository.countByStatusAndStartTimeBefore(
                    AuctionStatus.SCHEDULED, LocalDateTime.now());
            if (backlog > 0) {
                log.warn("【拍卖告警】{} 个拍品已过开始时间但仍未开拍，检查调度线程与数据库连接", backlog);
            } else {
                log.debug("【拍卖】开拍扫描正常，无积压");
            }
            // 限流窗口按用户维度存内存，不清会随用户数无限增长
            rateLimiter.evictIdle(300_000L);
        } catch (RuntimeException e) {
            log.warn("拍卖积压巡检失败", e);
        }
    }
}
