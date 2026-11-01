package com.starscreen.auction.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.stereotype.Component;

import com.starscreen.auction.config.AuctionProperties;

import lombok.RequiredArgsConstructor;

/**
 * 保证金定价策略：{@code clamp(起拍价 × ratio, min, cap)}（决策四 C）。
 *
 * <p><b>只在一个地方被调用</b>：{@code AuctionService.create}（发布拍品时）。
 * 报名、扣划、退款都只读 {@code auction_item.deposit} 这个快照，不重新计算。
 * 这条约束比公式本身重要得多——一旦有人在报名时重算，改了比例就会导致
 * "同一场拍卖里，早报名的人交了 500、晚报名的人交了 800"，退款时无法对账。
 *
 * <p><b>为什么不用固定金额</b>：高价拍品押 500 等于没有约束力，悔拍成本趋近于零。
 * <b>为什么不纯按比例</b>：10 万起拍按 5% 要押 5000，小额拍品按 5% 只押 25，
 * 两边都需要上下限兜住。
 */
@Component
@RequiredArgsConstructor
public class DepositPolicy {

    private final AuctionProperties properties;

    /**
     * 计算应缴保证金。
     *
     * @param startPrice 起拍价，null 或负数按 0 处理
     * @return 四舍五入到分，且必定 {@code > 0}
     */
    public BigDecimal calculate(BigDecimal startPrice) {
        AuctionProperties.Deposit cfg = properties.getDeposit();
        BigDecimal base = startPrice == null || startPrice.signum() < 0 ? BigDecimal.ZERO : startPrice;

        BigDecimal byRatio = base.multiply(cfg.getRatio()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal min = cfg.getMin() == null ? BigDecimal.ZERO : cfg.getMin();
        BigDecimal cap = cfg.getCap() == null ? byRatio.max(min) : cfg.getCap();

        BigDecimal result = byRatio.max(min).min(cap);
        // 兜底：配置写错（min=0 且 ratio=0）时也不允许出现零保证金
        return result.signum() <= 0 ? new BigDecimal("0.01") : result;
    }
}
