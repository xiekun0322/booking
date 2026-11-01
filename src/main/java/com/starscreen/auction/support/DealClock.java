package com.starscreen.auction.support;

import java.time.LocalDateTime;

import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.enums.DealStatus;

/**
 * 成交单的时间判定（全系统唯一实现）。
 *
 * <p><b>为什么必须只有一个实现</b>：决策三选的是"A（定时扫）+ C（读取时预判）"组合。
 * 两条路径如果各写一份判断，迟早出现"页面显示已超时、库里还是待支付"的矛盾——
 * 用户截图投诉时，你无法解释到底以哪个为准。
 * 所以判定逻辑收敛到本类，写路径（sweeper）和读路径（DTO 组装）都调它。
 *
 * <p><b>写路径与读路径的权限差异</b>：
 * <ul>
 *   <li>读路径只能<b>看</b>：把 {@code overdue=true} 展示成"已超时"，不改库；</li>
 *   <li>写路径只能<b>改</b>：由 sweeper 调条件 UPDATE 落库，是唯一权威。</li>
 * </ul>
 */
public final class DealClock {

    private DealClock() {
    }

    /** 是否已过支付截止时间。 */
    public static boolean isOverdue(AuctionDeal deal, LocalDateTime now) {
        return deal != null
                && deal.getStatus() == DealStatus.PENDING_PAYMENT
                && deal.getPayDeadline() != null
                && now.isAfter(deal.getPayDeadline());
    }

    /**
     * 现在还能不能付款。
     *
     * <p>比 {@link #isOverdue} 多一个状态判断：已取消/已支付的单子无论时间如何都不能再付。
     * 支付入口用它做第一道拦截，真正的幂等仍由条件 UPDATE 保证。
     */
    public static boolean payable(AuctionDeal deal, LocalDateTime now) {
        return deal != null
                && deal.getStatus() == DealStatus.PENDING_PAYMENT
                && deal.getPayDeadline() != null
                && !now.isAfter(deal.getPayDeadline());
    }

    /** 距离支付截止还剩多少秒（已过期返回 0，前端倒计时用）。 */
    public static long remainingSeconds(AuctionDeal deal, LocalDateTime now) {
        if (deal == null || deal.getPayDeadline() == null) {
            return 0;
        }
        long seconds = java.time.Duration.between(now, deal.getPayDeadline()).getSeconds();
        return Math.max(seconds, 0);
    }
}
