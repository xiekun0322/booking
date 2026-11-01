package com.starscreen.auction.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

/**
 * 拍卖模块配置（{@code app.auction.*}）。
 *
 * <p><b>所有阈值都可配</b>：拍卖的节奏（扫描间隔、顺延上限、支付期限）在不同业务上差异很大，
 * 写死在代码里会导致每次调整都要发版。默认值取"安全但不苛刻"的一档。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.auction")
public class AuctionProperties {

    /**
     * 总开关：关闭后拍卖相关的定时任务、MQ 消费者、模拟支付接口全部不装配。
     *
     * <p>注意 {@code false} 只停后台任务，不拦 HTTP 接口——真要下线模块应该是不部署，
     * 而不是留一堆能写库的接口在原地。
     */
    private boolean enabled = true;

    /** 开拍扫描单批条数。 */
    private int startBatchSize = 50;

    /** 关拍结算单批条数。 */
    private int settleBatchSize = 100;

    /**
     * 单场拍卖最多顺延次数。
     *
     * <p>没有上限时，持续出价可以让一场拍卖永不结束——这既是刷单工具，也让运营无法排期。
     */
    private int maxExtendTimes = 30;

    /** 存证模式：NONE（只记本地哈希）/ LOG / HTTP。 */
    private String chainMode = "NONE";

    /** 存证网关地址，chainMode=HTTP 时生效。 */
    private String chainEndpoint = "";

    // ==================== 保证金定价（决策四 C） ====================

    /**
     * 保证金规则：{@code clamp(起拍价 × ratio, min, cap)}。
     *
     * <p>金额在<b>发布时</b>算好写进 {@code auction_item.deposit}，报名只读该快照。
     * 这样调整比例/上下限只影响新发布的拍品，历史拍品的应缴与应退金额不会漂移。
     */
    private Deposit deposit = new Deposit();

    // ==================== 成交单（决策五 A） ====================

    private Deal deal = new Deal();

    // ==================== 发件箱 ====================

    private Outbox outbox = new Outbox();

    /** 保证金定价参数。 */
    @Data
    public static class Deposit {

        /** 按起拍价比例收取，0.05 表示 5%。 */
        private BigDecimal ratio = new BigDecimal("0.05");

        /** 下限：小额拍品也要有约束力。 */
        private BigDecimal min = new BigDecimal("500");

        /** 上限：高价拍品不要求用户一次押太多。 */
        private BigDecimal cap = new BigDecimal("50000");
    }

    /** 成交单与悔拍参数。 */
    @Data
    public static class Deal {

        /** 支付截止时间：关拍后多少小时内必须付款，超时判悔拍。 */
        private int payDeadlineHours = 24;

        /** 悔拍扫描单批条数。 */
        private int overdueSweepBatch = 50;

        /** 悔拍扫描间隔（毫秒）。独立于开拍/关拍扫描，避免互相阻塞。 */
        private long overdueSweepIntervalMs = 10_000L;

        /** 退款最大重试次数，超过转 REFUND_DEAD。 */
        private int refundMaxRetry = 5;

        /** 退款扫描单批条数。 */
        private int refundSweepBatch = 50;

        /** 退款扫描间隔（毫秒）。 */
        private long refundSweepIntervalMs = 15_000L;

        /** 退款退避基数（秒）：30、60、120、240、480… */
        private long refundRetryBaseSeconds = 30;
    }

    /** 发件箱参数。 */
    @Data
    public static class Outbox {

        /** relay 扫描间隔（毫秒）。 */
        private long relayIntervalMs = 3_000L;

        /** 单批投递条数。 */
        private int batchSize = 100;

        /** 最大重试次数，超过转 DEAD。 */
        private int maxRetry = 8;

        /** 租约时长（秒）：期内其它实例不碰同一条记录。 */
        private long leaseSeconds = 30;

        /** SENT 记录保留天数，过期分批删除。 */
        private int sentRetentionDays = 7;

        /** 每次清理的条数上限。 */
        private int purgeBatch = 1_000;
    }
}
