package com.starscreen.auction.pay;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.repository.AuctionDepositRepository;

import lombok.RequiredArgsConstructor;

/**
 * 保证金支付结果处理：PENDING_PAY → HELD（缴费成功才具备参拍资格）。
 *
 * <p><b>为什么失败/取消不删记录</b>：唯一键 {@code uk_deposit(user_id, item_id)} 规定了
 * "一个用户对一个拍品只有一条保证金记录"，删掉再建在并发下会撞键。
 * 所以失败/取消只改状态，重新支付时原地复活同一条记录。
 *
 * <p><b>为什么 {@code markHeld} 允许从 PAY_FAILED / CLOSED 进入</b>：
 * 用户支付失败后重试、或取消后重新报名，走的都是这条路径。
 * 若只允许从 PENDING_PAY 进入，那些用户在重新发起支付时回调会全部失败，
 * 表现为"付了钱但没资格出价"——这是最典型的资损类工单。
 */
@Component
@RequiredArgsConstructor
public class DepositPaymentHandler implements PaymentResultHandler {

    private static final Logger log = LoggerFactory.getLogger(DepositPaymentHandler.class);

    private final AuctionDepositRepository depositRepository;

    @Override
    public PayBizType bizType() {
        return PayBizType.DEPOSIT;
    }

    @Override
    public void onPaid(AuctionPayOrder order) {
        LocalDateTime now = LocalDateTime.now();
        int affected = depositRepository.markHeld(order.getBizId(), order.getPayNo(), now);
        if (affected == 0) {
            // 已经 HELD（重复回调）或已被罚没/退回（终态）——都属于幂等场景
            log.info("保证金到账处理无变更（重复回调或已终态） depositId={} payNo={}",
                    order.getBizId(), order.getPayNo());
            return;
        }
        log.info("【保证金】到账并冻结 depositId={} userId={} amount={} payNo={}",
                order.getBizId(), order.getUserId(), order.getAmount(), order.getPayNo());
    }

    @Override
    public void onFailed(AuctionPayOrder order) {
        int affected = depositRepository.markPayFailed(
                order.getBizId(), "支付失败:" + nullSafe(order.getFailReason()), LocalDateTime.now());
        if (affected > 0) {
            log.info("【保证金】支付失败，可重新发起 depositId={} reason={}",
                    order.getBizId(), order.getFailReason());
        }
    }

    @Override
    public void onCancelled(AuctionPayOrder order) {
        int affected = depositRepository.markClosed(
                order.getBizId(), "用户取消支付", LocalDateTime.now());
        if (affected > 0) {
            log.info("【保证金】用户取消支付，报名关闭 depositId={}", order.getBizId());
        }
    }

    private String nullSafe(String text) {
        return text == null ? "-" : text;
    }
}
