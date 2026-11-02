package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionDataSourceConfig;
import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.entity.AuctionNotification;
import com.starscreen.auction.enums.NotifyDeliveryStatus;
import com.starscreen.auction.notify.MailSender;
import com.starscreen.auction.repository.AuctionNotificationRepository;

import org.springframework.beans.factory.annotation.Qualifier;

/**
 * 通知外发 worker：把 PENDING/FAILED 的记录投递出去，失败按指数退避重试。
 *
 * <p><b>三段式</b>（与退款 worker 同款，理由相同）：
 * <ol>
 *   <li>事务内取待投递列表并读出发送所需字段；</li>
 *   <li><b>事务外</b>调通道——网关 RT 不可控，绝不能占着数据库事务；</li>
 *   <li>独立事务写结果（SENT / FAILED + 退避 / DEAD）。</li>
 * </ol>
 * 若把第 2 步放在事务里，一封邮件超时就会长时间持有拍卖库连接，
 * 退款、悔拍、出价全部排队等连接。
 */
@Service
public class NotificationDeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationDeliveryWorker.class);

    /** 单轮最多处理条数：避免一次扫描把线程占满。 */
    private static final int BATCH = 50;

    private final AuctionNotificationRepository repository;
    private final MailSender mailSender;
    private final AuctionProperties properties;
    private final TransactionTemplate tx;

    public NotificationDeliveryWorker(AuctionNotificationRepository repository,
                                      MailSender mailSender,
                                      AuctionProperties properties,
                                      @Qualifier(AuctionDataSourceConfig.AUCTION_TX_MANAGER)
                                      PlatformTransactionManager auctionTransactionManager) {
        this.repository = repository;
        this.mailSender = mailSender;
        this.properties = properties;
        this.tx = new TransactionTemplate(auctionTransactionManager);
    }

    /** 扫描并投递一轮。 */
    public void sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<AuctionNotification> due = tx.execute(s -> repository.findDeliverable(
                List.of(NotifyDeliveryStatus.PENDING, NotifyDeliveryStatus.FAILED),
                now, PageRequest.of(0, BATCH)));

        if (due == null || due.isEmpty()) {
            return;
        }
        for (AuctionNotification n : due) {
            try {
                deliver(n);
            } catch (RuntimeException e) {
                // 单条失败不影响其余记录
                log.warn("通知投递异常 id={}", n.getId(), e);
            }
        }
    }

    private void deliver(AuctionNotification n) {
        if (n.getEmail() == null || n.getEmail().isBlank()) {
            // 理论上写入时就置了 SKIPPED，这里兜底处理历史数据
            tx.execute(s -> repository.markDelivery(n.getId(), NotifyDeliveryStatus.SKIPPED,
                    n.getRetryCount() == null ? 0 : n.getRetryCount(), null, "无收件地址",
                    LocalDateTime.now()));
            return;
        }

        boolean ok;
        String error = null;
        try {
            ok = mailSender.send(n.getEmail(), n.getTitle(), n.getContent());
            if (!ok) {
                error = "通道拒绝发送";
            }
        } catch (RuntimeException e) {
            ok = false;
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }

        if (ok) {
            tx.execute(s -> repository.markSent(n.getId(), LocalDateTime.now(),
                    NotifyDeliveryStatus.SENT,
                    List.of(NotifyDeliveryStatus.PENDING, NotifyDeliveryStatus.FAILED)));
            return;
        }

        onFailure(n, error);
    }

    /** 失败退避：5s × 2^n，上限 300s；超过上限转 DEAD，不再自动重试。 */
    private void onFailure(AuctionNotification n, String error) {
        AuctionProperties.Notify cfg = properties.getNotify();
        int current = n.getRetryCount() == null ? 0 : n.getRetryCount();
        int next = current + 1;

        boolean dead = next > cfg.getMaxRetry();
        LocalDateTime nextRetryAt = dead ? null
                : LocalDateTime.now().plusSeconds(backoffSeconds(next, cfg.getBaseBackoffSeconds()));

        NotifyDeliveryStatus status = dead ? NotifyDeliveryStatus.DEAD : NotifyDeliveryStatus.FAILED;
        tx.execute(s -> repository.markDelivery(n.getId(), status, next, nextRetryAt,
                truncate(error), LocalDateTime.now()));

        if (dead) {
            log.error("【通知】投递重试耗尽，转 DEAD id={} userId={} type={} error={}",
                    n.getId(), n.getUserId(), n.getType(), error);
        } else {
            log.warn("【通知】投递失败，第 {} 次，{} 秒后重试 id={} error={}",
                    next, backoffSeconds(next, cfg.getBaseBackoffSeconds()), n.getId(), error);
        }
    }

    private long backoffSeconds(int attempt, long base) {
        long delay = base * (1L << Math.min(attempt - 1, 10));
        return Math.min(delay, 300L);
    }

    private String truncate(String v) {
        if (v == null) {
            return "未知错误";
        }
        return v.length() <= 500 ? v : v.substring(0, 500);
    }
}
