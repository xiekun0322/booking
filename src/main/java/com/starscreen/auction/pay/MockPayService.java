package com.starscreen.auction.pay;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.PayTicketDTO;
import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.MockPayResult;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.enums.PayStatus;
import com.starscreen.auction.repository.AuctionPayOrderRepository;
import com.starscreen.auction.support.AuctionIdGenerator;
import com.starscreen.common.BusinessException;

/**
 * 模拟支付通道。
 *
 * <p><b>为什么需要一个"模拟网关"而不是在业务里写 if</b>：真实支付链路的形状是
 * 「下单 → 收银台 → 异步通知 → 业务落账」。业务代码如果直接调"标记录为已支付"，
 * 等接真实网关时才发现：通知是异步的、会重复、会乱序、可能永远不来。
 * 现在用一个形状相同的模拟通道，把这些性质提前暴露出来，接真网关时只需替换本类。
 *
 * <p><b>通道状态与业务状态严格分离</b>：
 * <ul>
 *   <li>通道只关心 {@code auction_pay_order}（CREATED / PAID / FAILED / CANCELLED / REFUNDED）；</li>
 *   <li>业务后果由 {@link PaymentResultHandler} 决定（保证金 HELD、成交单 PAID、发件箱）。</li>
 * </ul>
 * 两者在同一个拍卖库事务里提交，所以"通道已扣款、业务没变化"这种半成品状态不会出现。
 *
 * <p><b>幂等</b>：重复通知是常态（网关重试 + 用户连点）。所有状态流转都是条件 UPDATE，
 * 第二次调用影响 0 行，且<b>不会重复触发业务处理器</b>——
 * 否则成交款会被记两次、保证金会退两次。
 */
@Service
public class MockPayService {

    private static final Logger log = LoggerFactory.getLogger(MockPayService.class);

    private final AuctionPayOrderRepository payOrderRepository;
    private final AuctionIdGenerator idGenerator;
    private final Map<PayBizType, PaymentResultHandler> handlers;

    public MockPayService(AuctionPayOrderRepository payOrderRepository,
                          AuctionIdGenerator idGenerator,
                          List<PaymentResultHandler> handlers) {
        this.payOrderRepository = payOrderRepository;
        this.idGenerator = idGenerator;
        this.handlers = new EnumMap<>(PayBizType.class);
        for (PaymentResultHandler handler : handlers) {
            this.handlers.put(handler.bizType(), handler);
        }
    }

    /**
     * 模拟下单：创建一笔支付单，返回收银台信息。
     *
     * <p>真实网关这一步会拿到 {@code prepay_id}；模拟实现只生成 {@code pay_no}，
     * 两者对外都是"一个可用于支付的凭证"，形状一致。
     */
    @AuctionTransactional
    public PayTicketDTO create(PayBizType bizType, Long bizId, Long userId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(400, "支付金额必须大于 0");
        }
        LocalDateTime now = LocalDateTime.now();
        AuctionPayOrder order = new AuctionPayOrder();
        order.setPayNo(idGenerator.nextBizNo("MP"));
        order.setBizType(bizType);
        order.setBizId(bizId);
        order.setUserId(userId);
        order.setAmount(amount);
        order.setStatus(PayStatus.CREATED);
        order.setChannel("MOCK");
        order.setCreateTime(now);
        order.setUpdateTime(now);
        payOrderRepository.save(order);

        log.info("【模拟支付】下单成功 payNo={} bizType={} bizId={} amount={}",
                order.getPayNo(), bizType, bizId, amount);
        return PayTicketDTO.of(order, checkoutUrl(order.getPayNo()));
    }

    @AuctionTransactional(readOnly = true)
    public AuctionPayOrder get(String payNo) {
        return payOrderRepository.findByPayNo(payNo)
                .orElseThrow(() -> new BusinessException(404, "支付单不存在：" + payNo));
    }

    @AuctionTransactional(readOnly = true)
    public PayTicketDTO ticket(String payNo) {
        AuctionPayOrder order = get(payNo);
        return PayTicketDTO.of(order, order.getStatus() == PayStatus.CREATED ? checkoutUrl(payNo) : null);
    }

    /**
     * 模拟网关回调：这是业务状态唯一的推进入口。
     *
     * <p>三种结果都走这里，与实际网关"先改通道状态、再通知业务"的顺序一致。
     *
     * @param payNo  支付单号
     * @param result SUCCESS / FAIL / CANCEL
     * @param reason 失败或取消原因（可空）
     */
    @AuctionTransactional
    public AuctionPayOrder notify(String payNo, MockPayResult result, String reason) {
        AuctionPayOrder order = payOrderRepository.findByPayNo(payNo)
                .orElseThrow(() -> new BusinessException(404, "支付单不存在：" + payNo));
        LocalDateTime now = LocalDateTime.now();
        String safeReason = reason == null || reason.isBlank() ? defaultReason(result) : reason;

        switch (result) {
            case SUCCESS -> {
                int affected = payOrderRepository.markPaid(payNo, now);
                AuctionPayOrder current = payOrderRepository.findByPayNo(payNo).orElseThrow();
                if (affected == 1) {
                    handlerOf(current.getBizType()).onPaid(current);
                } else if (current.getStatus() != PayStatus.PAID) {
                    // 已退款/已关闭的单子又收到成功通知：通道与业务数据不一致，必须暴露
                    throw new BusinessException(409,
                            "支付单当前状态 " + current.getStatus() + " 不接受支付成功回调");
                } else {
                    log.info("【模拟支付】重复的成功回调，已幂等忽略 payNo={}", payNo);
                }
                return current;
            }
            case FAIL -> {
                int affected = payOrderRepository.markFailed(payNo, safeReason, now);
                AuctionPayOrder current = payOrderRepository.findByPayNo(payNo).orElseThrow();
                if (affected == 1) {
                    handlerOf(current.getBizType()).onFailed(current);
                } else {
                    log.info("【模拟支付】失败回调无变更（状态={}） payNo={}", current.getStatus(), payNo);
                }
                return current;
            }
            case CANCEL -> {
                int affected = payOrderRepository.markCancelled(payNo, safeReason, now);
                AuctionPayOrder current = payOrderRepository.findByPayNo(payNo).orElseThrow();
                if (affected == 1) {
                    handlerOf(current.getBizType()).onCancelled(current);
                } else {
                    log.info("【模拟支付】取消回调无变更（状态={}） payNo={}", current.getStatus(), payNo);
                }
                return current;
            }
            default -> throw new BusinessException(400, "不支持的支付结果：" + result);
        }
    }

    /**
     * 模拟原路退款（由退款 worker 调用）。
     *
     * @return true 表示通道确认退款成功
     */
    @AuctionTransactional
    public boolean refund(String payNo) {
        int affected = payOrderRepository.markRefunded(payNo, LocalDateTime.now());
        if (affected == 1) {
            log.info("【模拟支付】退款成功 payNo={}", payNo);
            return true;
        }
        AuctionPayOrder current = payOrderRepository.findByPayNo(payNo).orElse(null);
        if (current != null && current.getStatus() == PayStatus.REFUNDED) {
            // 重复退款请求：通道已经退过了，对调用方视为成功（幂等）
            log.info("【模拟支付】重复退款，通道已退过 payNo={}", payNo);
            return true;
        }
        log.warn("【模拟支付】退款失败，支付单状态不允许退款 payNo={} status={}",
                payNo, current == null ? "NOT_FOUND" : current.getStatus());
        return false;
    }

    // ==================== 内部 ====================

    private PaymentResultHandler handlerOf(PayBizType bizType) {
        PaymentResultHandler handler = handlers.get(bizType);
        if (handler == null) {
            throw new BusinessException(500, "缺少支付结果处理器：" + bizType);
        }
        return handler;
    }

    private String checkoutUrl(String payNo) {
        return "/api/auction/mock-pay/checkout?payNo=" + payNo;
    }

    private String defaultReason(MockPayResult result) {
        return switch (result) {
            case SUCCESS -> null;
            case FAIL -> "模拟支付失败";
            case CANCEL -> "用户取消支付";
        };
    }
}
