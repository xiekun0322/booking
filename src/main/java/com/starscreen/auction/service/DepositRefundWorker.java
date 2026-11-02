package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.entity.AuctionDeposit;
import com.starscreen.auction.enums.NotificationType;
import com.starscreen.auction.pay.MockPayService;
import com.starscreen.auction.repository.AuctionDepositRepository;

/**
 * 保证金退款 worker：把"该退的钱"原路退回。
 *
 * <p><b>为什么不能一个事务干完</b>：退款要调支付通道（网络 IO）。
 * 如果把它包在事务里，通道超时会一直占着数据库连接与行锁，
 * 一批 50 条卡住 60 秒，拍卖库的连接池（16）会被迅速打光，
 * 连带出价、开拍、列表全部变慢。所以拆成三段：
 * <pre>
 *   ① 事务：HELD → REFUNDING（抢占退款权，先提交）
 *   ② 事务外：调用通道退款
 *   ③ 事务：标记 REFUNDED，或退回 HELD + 退避重试
 * </pre>
 *
 * <p><b>为什么 ① 必须先提交</b>：REFUNDING 就是"这笔退款我正在处理"的分布式锁。
 * 不提交的话，另一个实例读到的还是 HELD，会重复发起退款——
 * 用户收到两笔钱，比"晚退几分钟"严重得多。
 *
 * <p><b>退款失败不回滚付款</b>：退款与付款是两条独立链路。
 * 失败只影响本条记录（回到 HELD 等下次重试），绝不触碰成交单状态。
 */
@Service
public class DepositRefundWorker {

    private static final Logger log = LoggerFactory.getLogger(DepositRefundWorker.class);

    private final AuctionDepositRepository depositRepository;
    private final MockPayService payService;
    private final AuctionProperties properties;
    private final NotificationService notificationService;
    private final TransactionTemplate tx;

    public DepositRefundWorker(AuctionDepositRepository depositRepository,
                               MockPayService payService,
                               AuctionProperties properties,
                               NotificationService notificationService,
                               @Qualifier("auctionTransactionManager") PlatformTransactionManager auctionTransactionManager) {
        this.depositRepository = depositRepository;
        this.payService = payService;
        this.properties = properties;
        this.notificationService = notificationService;
        this.tx = new TransactionTemplate(auctionTransactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 扫描并处理一批待退款记录。
     *
     * @return 本批真正退款成功的条数
     */
    public int sweepRefund() {
        AuctionProperties.Deal cfg = properties.getDeal();
        List<AuctionDeposit> pending = depositRepository.findRefundable(
                LocalDateTime.now(), cfg.getRefundSweepBatch());
        if (pending.isEmpty()) {
            return 0;
        }

        int refunded = 0;
        for (AuctionDeposit deposit : pending) {
            try {
                if (refundOne(deposit)) {
                    refunded++;
                }
            } catch (RuntimeException e) {
                // 单条失败不影响其它条：本条已随自己的事务回滚，下一轮再试
                log.error("保证金退款处理异常，itemId={} userId={}，下一轮重试",
                        deposit.getItemId(), deposit.getUserId(), e);
            }
        }
        if (refunded > 0) {
            log.info("【保证金】本批退款成功 {} 条", refunded);
        }
        return refunded;
    }

    // ==================== 内部 ====================

    private boolean refundOne(AuctionDeposit deposit) {
        if (deposit.getPayNo() == null || deposit.getPayNo().isBlank()) {
            // 没有支付单号无法原路退回，属于数据异常，直接转人工
            tx.execute(s -> depositRepository.markRefundDead(
                    deposit.getId(), "缺少支付单号，无法原路退回", LocalDateTime.now()));
            log.error("【保证金】缺少支付单号，转 REFUND_DEAD depositId={} itemId={}",
                    deposit.getId(), deposit.getItemId());
            return false;
        }

        // ① 抢占退款权（独立事务并提交）
        LocalDateTime now = LocalDateTime.now();
        Boolean claimed = tx.execute(s -> depositRepository.claimRefund(deposit.getId(), now) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            return false;
        }

        // ② 调通道（事务外）
        try {
            boolean ok = payService.refund(deposit.getPayNo());
            if (!ok) {
                onFailure(deposit, "通道拒绝退款");
                return false;
            }
        } catch (RuntimeException e) {
            onFailure(deposit, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }

        // ③ 标记成功（独立事务）
        tx.execute(s -> depositRepository.markRefunded(deposit.getId(), LocalDateTime.now()));

        // 通知在退款已落库之后发：用户收到的第一条信息必须是"钱已经退了"，
        // 而不是"正在退"——后者会引发大量"为什么还没到账"的追问。
        notificationService.notify(deposit.getUserId(), NotificationType.DEPOSIT_REFUNDED,
                "保证金已退回",
                "您在拍品 #" + deposit.getItemId() + " 缴纳的保证金 ¥" + deposit.getAmount()
                        + " 已原路退回，请留意原支付渠道到账。",
                "DEPOSIT", deposit.getId());

        log.info("【保证金】退款成功 depositId={} itemId={} userId={} amount={} payNo={}",
                deposit.getId(), deposit.getItemId(), deposit.getUserId(),
                deposit.getAmount(), deposit.getPayNo());
        return true;
    }

    private void onFailure(AuctionDeposit deposit, String error) {
        AuctionProperties.Deal cfg = properties.getDeal();
        int current = deposit.getRetryCount() == null ? 0 : deposit.getRetryCount();
        int next = current + 1;
        String message = truncate(error);

        if (next > cfg.getRefundMaxRetry()) {
            tx.execute(s -> depositRepository.markRefundDead(deposit.getId(), message, LocalDateTime.now()));
            log.error("【保证金】退款重试耗尽转 REFUND_DEAD，需人工处理 depositId={} itemId={} payNo={} err={}",
                    deposit.getId(), deposit.getItemId(), deposit.getPayNo(), message);
            return;
        }

        long backoff = cfg.getRefundRetryBaseSeconds() * (1L << Math.min(next - 1, 6));
        LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoff);
        tx.execute(s -> depositRepository.markRefundRetry(
                deposit.getId(), next, nextRetryAt, message, LocalDateTime.now()));
        log.warn("【保证金】退款失败，{} 秒后第 {} 次重试 depositId={} err={}",
                backoff, next, deposit.getId(), message);
    }

    private String truncate(String text) {
        if (text == null) {
            return "-";
        }
        return text.length() <= 500 ? text : text.substring(0, 500);
    }
}
