package com.starscreen.auction.pay;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.config.AuctionRabbitConfig;
import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.DealStatus;
import com.starscreen.auction.enums.NotificationType;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.mq.OutboxWriter;
import com.starscreen.auction.repository.AuctionDealRepository;
import com.starscreen.auction.service.NotificationService;
import com.starscreen.auction.support.AuctionAuditWriter;

import lombok.RequiredArgsConstructor;

/**
 * 成交款支付结果处理。
 *
 * <p><b>成功时做两件事，且必须同事务</b>：
 * <ol>
 *   <li>{@code deal.status: PENDING_PAYMENT → PAID}；</li>
 *   <li>写发件箱 {@code DEPOSIT_REFUND_TRIGGER}(includeUserId = buyerId)，
 *       由异步消费者写 {@code refund_trigger_at}，worker 随后原路退回。</li>
 * </ol>
 * 这两件事在一个本地事务里（同在拍卖库），所以<b>不可能出现"退了保证金但成交款没到账"</b>
 * ——这正是决策 D4"付款成功后才退"的实现保证。
 *
 * <p><b>为什么退款还要绕一层发件箱</b>（决策二 B）：为了与未中标退款（决策一 C）、
 * 悔拍罚没（决策三 D）共用同一个消费者、同一套幂等与监控。
 * 三条路径写同一个 {@code refund_trigger_at}，排查"这个人为什么没退"时只需要看一张表。
 *
 * <p><b>超时后付款的边界</b>：如果 sweeper 已经把单子判成 CANCELLED（悔拍），
 * 而用户在之后才付款成功，{@code markPaid} 会返回 0。这时钱已经收了货却没了，
 * 必须显式记 ERROR 交给人工退款——不能静默，也不能自动把单子改回 PAID
 * （拍卖已经按悔拍处置过保证金，回退会破坏已发生的事实）。
 */
@Component
@RequiredArgsConstructor
public class DealPaymentHandler implements PaymentResultHandler {

    private static final Logger log = LoggerFactory.getLogger(DealPaymentHandler.class);

    private final AuctionDealRepository dealRepository;
    private final OutboxWriter outboxWriter;
    private final AuctionAuditWriter auditWriter;
    private final NotificationService notificationService;

    @Override
    public PayBizType bizType() {
        return PayBizType.DEAL;
    }

    @Override
    public void onPaid(AuctionPayOrder order) {
        LocalDateTime now = LocalDateTime.now();
        int affected = dealRepository.markPaid(order.getBizId(), now);

        if (affected == 0) {
            AuctionDeal current = dealRepository.findById(order.getBizId()).orElse(null);
            if (current != null && current.getStatus() == DealStatus.CANCELLED) {
                log.error("【拍卖】成交单已判悔拍但成交款支付成功，钱收了货没了，需人工退款 "
                                + "dealId={} dealNo={} payNo={} amount={}",
                        current.getId(), current.getDealNo(), order.getPayNo(), order.getAmount());
            } else {
                log.info("成交款到账处理无变更（重复回调） dealId={} payNo={}",
                        order.getBizId(), order.getPayNo());
            }
            return;
        }

        AuctionDeal deal = dealRepository.findById(order.getBizId()).orElse(null);
        if (deal == null) {
            log.error("成交款已到账但成交单查不到，数据异常 dealId={}", order.getBizId());
            return;
        }

        // 付款成功 → 触发中标者自己的保证金退款（只退他一个人）
        AuctionDepositEvent event = AuctionDepositEvent.builder()
                .eventType("DEPOSIT_REFUND_TRIGGER")
                .itemId(deal.getItemId())
                .includeUserId(deal.getBuyerId())
                .reason("DEAL_PAID")
                .occurredAt(now)
                .build();
        outboxWriter.write("AUCTION_DEAL", deal.getId(), "DEPOSIT_REFUND_TRIGGER",
                AuctionRabbitConfig.DEPOSIT_ROUTING_KEY, event);

        auditWriter.write("DEAL", deal.getId(), deal.getBuyerId(), "USER", "DEAL_PAID",
                "dealNo=" + deal.getDealNo() + ", amount=" + deal.getDealPrice()
                        + ", payNo=" + order.getPayNo(), null);

        notificationService.notify(deal.getBuyerId(), NotificationType.DEAL_PAID,
                "成交款已到账",
                "成交单 " + deal.getDealNo() + " 的成交款 ¥" + deal.getDealPrice()
                        + " 已支付成功，保证金将按规则原路退回，请留意到账。",
                "DEAL", deal.getId());

        log.info("【拍卖】成交款到账 dealId={} dealNo={} amount={}，已投递保证金退款触发",
                deal.getId(), deal.getDealNo(), deal.getDealPrice());
    }

    /**
     * 支付失败：单子保持 PENDING_PAYMENT，用户可以重试。
     *
     * <p>不改状态是刻意的：支付失败不等于悔拍，悔拍的唯一判据是"到了截止时间还没付"。
     * 把失败当悔拍会导致"用户点错一次就丢了保证金"。
     */
    @Override
    public void onFailed(AuctionPayOrder order) {
        log.info("【拍卖】成交款支付失败，单据保持待支付，可重试 dealId={} reason={}",
                order.getBizId(), order.getFailReason());
    }

    /** 用户取消支付：同样保持 PENDING_PAYMENT，等待重新支付或到期悔拍。 */
    @Override
    public void onCancelled(AuctionPayOrder order) {
        log.info("【拍卖】用户取消成交款支付，单据保持待支付 dealId={}", order.getBizId());
    }
}
