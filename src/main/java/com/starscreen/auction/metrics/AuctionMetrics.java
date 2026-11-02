package com.starscreen.auction.metrics;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionDataSourceConfig;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.enums.DealStatus;
import com.starscreen.auction.enums.DepositStatus;
import com.starscreen.auction.enums.NotifyDeliveryStatus;
import com.starscreen.auction.enums.OutboxStatus;
import com.starscreen.auction.repository.AuctionDealRepository;
import com.starscreen.auction.repository.AuctionDepositRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.repository.AuctionNotificationRepository;
import com.starscreen.auction.repository.AuctionOutboxRepository;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 拍卖资金链路的监控指标。
 *
 * <p><b>为什么是"查库型"指标而不是计数器</b>：这里要回答的是"现在积压了多少"，
 * 而不是"发生过多少次"。计数器无法反映积压回落，也无法发现
 * "扫描任务压根没跑"这类"什么都没发生"的故障——而拍卖里最危险的恰恰是这种：
 * 发件箱不投递、退款不执行、悔拍不判定，都表现为"零事件"。
 *
 * <p><b>为什么每次抓取都查库</b>：actuator 的 metrics 端点只在被请求时抓取
 * （默认无人轮询就不会执行），Prometheus 拉取间隔通常 15–60s，
 * 7 条 COUNT 查询的代价远低于它带来的可见性。若将来接入高频抓取，
 * 应改为定时任务写入 Gauge 持有的变量，而不是缩短抓取间隔。
 *
 * <p><b>失败返回 -1 的原因</b>：指标查询失败绝不能影响业务，
 * 但也必须能看出来——{@code -1} 与"确实为 0"在图表上完全不同。
 *
 * <p><b>构造器为什么手写</b>：Lombok 的 {@code @RequiredArgsConstructor} 不会把字段上的
 * {@code @Qualifier} 复制到构造参数，注入时会退回按类型匹配并选中 {@code @Primary}
 * 的业务库事务管理器——结果是在业务库上查拍卖表，所有指标恒为 -1。
 */
@Component
public class AuctionMetrics {

    private static final Logger log = LoggerFactory.getLogger(AuctionMetrics.class);

    /** 查询失败时的哨兵值：与真实的 0 区分开。 */
    private static final double QUERY_FAILED = -1d;

    private final AuctionOutboxRepository outboxRepository;
    private final AuctionDepositRepository depositRepository;
    private final AuctionDealRepository dealRepository;
    private final AuctionItemRepository itemRepository;
    private final AuctionNotificationRepository notificationRepository;
    private final TransactionTemplate tx;

    public AuctionMetrics(AuctionOutboxRepository outboxRepository,
                          AuctionDepositRepository depositRepository,
                          AuctionDealRepository dealRepository,
                          AuctionItemRepository itemRepository,
                          AuctionNotificationRepository notificationRepository,
                          @Qualifier(AuctionDataSourceConfig.AUCTION_TX_MANAGER)
                          PlatformTransactionManager auctionTransactionManager,
                          MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        this.depositRepository = depositRepository;
        this.dealRepository = dealRepository;
        this.itemRepository = itemRepository;
        this.notificationRepository = notificationRepository;

        // 必须显式指定拍卖库的事务管理器：metrics 抓取线程上没有事务上下文，
        // 走默认（@Primary 业务库）会去业务库上查拍卖表，直接报表不存在。
        this.tx = new TransactionTemplate(auctionTransactionManager);
        this.tx.setReadOnly(true);

        register(registry);
    }

    private void register(MeterRegistry registry) {
        gauge(registry, "auction.outbox.pending",
                "发件箱待投递条数（持续大于 0 说明 relay 没在跑或 broker 不可用）",
                this::outboxPending);
        gauge(registry, "auction.outbox.dead",
                "发件箱死信件数（需人工重放，不自动重投）",
                this::outboxDead);
        gauge(registry, "auction.item.scheduled.backlog",
                "已过开始时间但仍为待拍的条数（开拍扫描滞后）",
                this::scheduledBacklog);
        gauge(registry, "auction.deposit.refund.backlog",
                "已到退款时间点但未退完的条数（HELD + REFUNDING）",
                this::refundBacklog);
        gauge(registry, "auction.deposit.refund.dead",
                "退款死信件数（已放弃自动重试，需人工处理）",
                this::refundDead);
        gauge(registry, "auction.deal.overdue",
                "待支付且已过支付截止时间的成交单数（悔拍扫描滞后）",
                this::dealOverdue);
        gauge(registry, "auction.deal.pending",
                "待支付成交单总数（含未到期）",
                this::dealPending);
        gauge(registry, "auction.notify.dead",
                "通知外发死信件数（站内信仍在，仅邮件未送达）",
                this::notifyDead);
    }

    // ==================== 指标取值 ====================

    private double outboxPending() {
        return query(() -> outboxRepository.countByStatus(OutboxStatus.PENDING));
    }

    private double outboxDead() {
        return query(() -> outboxRepository.countByStatus(OutboxStatus.DEAD));
    }

    private double scheduledBacklog() {
        LocalDateTime now = LocalDateTime.now();
        return query(() -> itemRepository.countByStatusAndStartTimeBefore(AuctionStatus.SCHEDULED, now));
    }

    private double refundBacklog() {
        LocalDateTime now = LocalDateTime.now();
        // 触发后状态立刻离开 HELD，所以必须把 REFUNDING 一起算，否则指标恒为 0
        return query(() -> depositRepository.countRefundBacklog(
                List.of(DepositStatus.HELD, DepositStatus.REFUNDING), now));
    }

    private double refundDead() {
        return query(() -> depositRepository.countByStatus(DepositStatus.REFUND_DEAD));
    }

    private double dealOverdue() {
        LocalDateTime now = LocalDateTime.now();
        return query(() -> dealRepository.countByStatusAndPayDeadlineBefore(DealStatus.PENDING_PAYMENT, now));
    }

    private double dealPending() {
        return query(() -> dealRepository.countByStatus(DealStatus.PENDING_PAYMENT));
    }

    private double notifyDead() {
        return query(() -> notificationRepository.countByDeliveryStatus(NotifyDeliveryStatus.DEAD));
    }

    // ==================== 内部 ====================

    private void gauge(MeterRegistry registry, String name, String description,
                       Supplier<Double> valueSupplier) {
        Gauge.builder(name, this, self -> valueSupplier.get())
                .description(description)
                .register(registry);
    }

    /**
     * 在拍卖库只读事务内执行查询，异常降级为 {@link #QUERY_FAILED}。
     *
     * <p>指标接口被监控系统高频调用，任何一次抛异常都会让整个 metrics 端点 500，
     * 连带其它健康指标一起消失——所以这里吞掉异常只留 WARN，并由 -1 表达"取不到"。
     */
    private double query(Supplier<? extends Number> supplier) {
        try {
            Number value = tx.execute(status -> supplier.get());
            return value == null ? QUERY_FAILED : value.doubleValue();
        } catch (RuntimeException e) {
            log.warn("拍卖监控指标查询失败：{}", e.getMessage());
            return QUERY_FAILED;
        }
    }
}
