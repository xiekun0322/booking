package com.starscreen.auction.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.starscreen.auction.config.AuctionProperties;

/**
 * 保证金定价单测（决策四 C）。
 *
 * <p>验证三件事：比例计算正确、上下限真的生效、任何配置下都不会算出 0 或负数。
 * 最后一条是资金安全的底线——零保证金意味着悔拍零成本。
 */
class DepositPolicyTest {

    private DepositPolicy policyOf(String ratio, String min, String cap) {
        AuctionProperties properties = new AuctionProperties();
        properties.getDeposit().setRatio(new BigDecimal(ratio));
        properties.getDeposit().setMin(new BigDecimal(min));
        properties.getDeposit().setCap(new BigDecimal(cap));
        return new DepositPolicy(properties);
    }

    @Test
    @DisplayName("区间内按比例：起拍价 10000 × 5% = 500")
    void shouldUseRatioWithinRange() {
        assertThat(policyOf("0.05", "100", "50000").calculate(new BigDecimal("10000")))
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("低于下限按下限：起拍价 1000 × 5% = 50 → 取 500")
    void shouldClampToMin() {
        assertThat(policyOf("0.05", "500", "50000").calculate(new BigDecimal("1000")))
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("高于上限按上限：起拍价 100万 × 5% = 5万 → 取 2万")
    void shouldClampToCap() {
        assertThat(policyOf("0.05", "500", "20000").calculate(new BigDecimal("1000000")))
                .isEqualByComparingTo("20000.00");
    }

    @Test
    @DisplayName("比例计算结果四舍五入到分")
    void shouldRoundToCent() {
        // 333.33 × 3% = 9.9999 → 10.00
        assertThat(policyOf("0.03", "1", "50000").calculate(new BigDecimal("333.33")))
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("配置异常时兜底为 0.01，绝不返回 0")
    void shouldNeverReturnZero() {
        assertThat(policyOf("0", "0", "0").calculate(new BigDecimal("100")))
                .isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("起拍价为 null 时按下限处理，不抛异常")
    void shouldHandleNullStartPrice() {
        assertThat(policyOf("0.05", "500", "50000").calculate(null))
                .isEqualByComparingTo("500.00");
    }
}
