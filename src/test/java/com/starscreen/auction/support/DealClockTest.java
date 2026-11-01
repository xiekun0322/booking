package com.starscreen.auction.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.enums.DealStatus;

/**
 * 悔拍时间判定单测（决策三 D）。
 *
 * <p>这套判定同时被 sweeper（写）和页面（读）使用，一旦两处不一致就会出现
 * "页面显示已超时、库里还是待支付"。所以边界必须由测试锁死：
 * 正好等于截止时间<b>不算</b>超时（与 SQL 的 {@code pay_deadline < now} 保持一致）。
 */
class DealClockTest {

    private static final LocalDateTime DEADLINE = LocalDateTime.of(2026, 11, 1, 12, 0, 0);

    private AuctionDeal deal(DealStatus status, LocalDateTime deadline) {
        AuctionDeal deal = new AuctionDeal();
        deal.setStatus(status);
        deal.setPayDeadline(deadline);
        return deal;
    }

    @Test
    @DisplayName("截止时间之后 → 已超时")
    void shouldBeOverdueAfterDeadline() {
        AuctionDeal d = deal(DealStatus.PENDING_PAYMENT, DEADLINE);
        assertThat(DealClock.isOverdue(d, DEADLINE.plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("正好等于截止时间 → 不算超时（与 SQL 的 < 对齐）")
    void shouldNotBeOverdueAtDeadline() {
        AuctionDeal d = deal(DealStatus.PENDING_PAYMENT, DEADLINE);
        assertThat(DealClock.isOverdue(d, DEADLINE)).isFalse();
        assertThat(DealClock.payable(d, DEADLINE)).isTrue();
    }

    @Test
    @DisplayName("已支付 / 已取消的单子永远不算超时")
    void shouldNotBeOverdueForNonPending() {
        assertThat(DealClock.isOverdue(deal(DealStatus.PAID, DEADLINE), DEADLINE.plusDays(1))).isFalse();
        assertThat(DealClock.isOverdue(deal(DealStatus.CANCELLED, DEADLINE), DEADLINE.plusDays(1))).isFalse();
    }

    @Test
    @DisplayName("截止时间为空时不判超时（历史数据兼容）")
    void shouldNotBeOverdueWithoutDeadline() {
        assertThat(DealClock.isOverdue(deal(DealStatus.PENDING_PAYMENT, null), DEADLINE)).isFalse();
    }

    @Test
    @DisplayName("剩余秒数：未到期返回正数，已过期归零")
    void shouldComputeRemainingSeconds() {
        AuctionDeal d = deal(DealStatus.PENDING_PAYMENT, DEADLINE);
        assertThat(DealClock.remainingSeconds(d, DEADLINE.minusMinutes(1))).isEqualTo(60);
        assertThat(DealClock.remainingSeconds(d, DEADLINE.plusMinutes(1))).isZero();
    }
}
