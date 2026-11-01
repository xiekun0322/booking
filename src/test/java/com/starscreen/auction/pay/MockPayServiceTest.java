package com.starscreen.auction.pay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.MockPayResult;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.enums.PayStatus;
import com.starscreen.auction.repository.AuctionPayOrderRepository;
import com.starscreen.auction.support.AuctionIdGenerator;
import com.starscreen.common.BusinessException;

/**
 * 模拟支付通道单测：验证"重复通知不会把业务做两遍"。
 *
 * <p>这是资金链路最关键的一条不变式。网关重试、用户连点、网络抖动都会产生重复通知；
 * 如果 {@code onPaid} 被调用两次，成交款会记两次、保证金会退两次。
 * 本测试把这条约束钉在 {@code markPaid 返回 0 就不调 handler} 上。
 */
@ExtendWith(MockitoExtension.class)
class MockPayServiceTest {

    @Mock private AuctionPayOrderRepository payOrderRepository;
    @Mock private AuctionIdGenerator idGenerator;
    @Mock private PaymentResultHandler depositHandler;

    private MockPayService payService;

    @BeforeEach
    void setUp() {
        when(depositHandler.bizType()).thenReturn(PayBizType.DEPOSIT);
        payService = new MockPayService(payOrderRepository, idGenerator, List.of(depositHandler));
    }

    private AuctionPayOrder order(PayStatus status) {
        AuctionPayOrder order = new AuctionPayOrder();
        order.setPayNo("MP1");
        order.setBizType(PayBizType.DEPOSIT);
        order.setBizId(100L);
        order.setUserId(9L);
        order.setAmount(new BigDecimal("500.00"));
        order.setStatus(status);
        return order;
    }

    @Test
    @DisplayName("首次成功回调：通道置 PAID 且业务处理器被调用一次")
    void shouldCallHandlerOnFirstSuccess() {
        AuctionPayOrder created = order(PayStatus.CREATED);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(created));
        when(payOrderRepository.markPaid(anyString(), any())).thenReturn(1);

        payService.notify("MP1", MockPayResult.SUCCESS, null);

        verify(depositHandler, times(1)).onPaid(any());
    }

    @Test
    @DisplayName("重复成功回调：业务处理器不再被调用（钱不会记两次）")
    void shouldIgnoreDuplicateSuccess() {
        AuctionPayOrder paid = order(PayStatus.PAID);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(paid));
        when(payOrderRepository.markPaid(anyString(), any())).thenReturn(0);

        payService.notify("MP1", MockPayResult.SUCCESS, null);

        verify(depositHandler, never()).onPaid(any());
    }

    @Test
    @DisplayName("已退款的支付单又收到成功回调：抛 409，不静默改状态")
    void shouldRejectSuccessOnRefundedOrder() {
        AuctionPayOrder refunded = order(PayStatus.REFUNDED);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(refunded));
        when(payOrderRepository.markPaid(anyString(), any())).thenReturn(0);

        assertThatThrownBy(() -> payService.notify("MP1", MockPayResult.SUCCESS, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不接受支付成功回调");
        verify(depositHandler, never()).onPaid(any());
    }

    @Test
    @DisplayName("失败回调：只通知失败处理器，不触发成功逻辑")
    void shouldCallFailedHandler() {
        AuctionPayOrder created = order(PayStatus.CREATED);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(created));
        when(payOrderRepository.markFailed(anyString(), anyString(), any())).thenReturn(1);

        payService.notify("MP1", MockPayResult.FAIL, "余额不足");

        verify(depositHandler, times(1)).onFailed(any());
        verify(depositHandler, never()).onPaid(any());
    }

    @Test
    @DisplayName("取消回调：只通知取消处理器")
    void shouldCallCancelledHandler() {
        AuctionPayOrder created = order(PayStatus.CREATED);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(created));
        when(payOrderRepository.markCancelled(anyString(), anyString(), any())).thenReturn(1);

        payService.notify("MP1", MockPayResult.CANCEL, null);

        verify(depositHandler, times(1)).onCancelled(any());
        verify(depositHandler, never()).onPaid(any());
    }

    @Test
    @DisplayName("退款：通道确认成功返回 true，重复退款也返回 true（幂等）")
    void shouldBeIdempotentOnRefund() {
        when(payOrderRepository.markRefunded(anyString(), any())).thenReturn(1);
        assertThat(payService.refund("MP1")).isTrue();

        when(payOrderRepository.markRefunded(anyString(), any())).thenReturn(0);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(order(PayStatus.REFUNDED)));
        assertThat(payService.refund("MP1")).isTrue();
    }

    @Test
    @DisplayName("退款：未支付的支付单不允许退款")
    void shouldRejectRefundOnUnpaidOrder() {
        when(payOrderRepository.markRefunded(anyString(), any())).thenReturn(0);
        when(payOrderRepository.findByPayNo("MP1")).thenReturn(Optional.of(order(PayStatus.CREATED)));
        assertThat(payService.refund("MP1")).isFalse();
    }

    @Test
    @DisplayName("下单金额必须大于 0")
    void shouldRejectNonPositiveAmount() {
        assertThatThrownBy(() -> payService.create(PayBizType.DEPOSIT, 1L, 9L, BigDecimal.ZERO))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("必须大于 0");
    }
}
