package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.enums.DealStatus;
import com.starscreen.auction.repository.AuctionDealRepository;

/**
 * 悔拍扫描（决策三 D 的"写路径"）。
 *
 * <p><b>为什么悔拍只能靠定时扫</b>：悔拍的定义是"到期没有付款"——这是一个<b>不存在的事件</b>。
 * 支付通道只在付款成功时通知，不会告诉你"这个用户没付"。任何"等回调判悔拍"的设计
 * 都会让成交单永久停在 PENDING_PAYMENT、保证金永久冻结。
 *
 * <p><b>每条独立事务</b>（{@code REQUIRES_NEW}）：一条判失败不能拖垮整批。
 * 这与 {@code SettlementSweeper} 是同一套教训——批事务里 catch 异常只能挡住异常外抛，
 * 挡不住事务被标记 rollback-only。
 *
 * <p><b>读路径不在这里</b>：页面上的"已超时"由 {@code DealClock} 计算展示，
 * 不写库。判定逻辑只有一份，避免页面与库不一致。
 */
@Service
public class DealOverdueSweeper {

    private static final Logger log = LoggerFactory.getLogger(DealOverdueSweeper.class);

    private final AuctionDealRepository dealRepository;
    private final DealService dealService;
    private final AuctionProperties properties;
    private final TransactionTemplate perDealTx;

    public DealOverdueSweeper(AuctionDealRepository dealRepository,
                              DealService dealService,
                              AuctionProperties properties,
                              @Qualifier("auctionTransactionManager") PlatformTransactionManager auctionTransactionManager) {
        this.dealRepository = dealRepository;
        this.dealService = dealService;
        this.properties = properties;
        this.perDealTx = new TransactionTemplate(auctionTransactionManager);
        this.perDealTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 扫描并处理一批超时成交单。
     *
     * @return 本次真正判定为悔拍的条数
     */
    public int sweepOverdue() {
        int batch = Math.max(1, properties.getDeal().getOverdueSweepBatch());
        List<AuctionDeal> overdue = dealRepository
                .findTop100ByStatusAndPayDeadlineBeforeOrderByPayDeadlineAsc(
                        DealStatus.PENDING_PAYMENT, LocalDateTime.now());
        if (overdue.isEmpty()) {
            return 0;
        }

        int cancelled = 0;
        for (AuctionDeal deal : overdue.stream().limit(batch).toList()) {
            try {
                Boolean done = perDealTx.execute(s -> dealService.cancelOverdue(deal.getId()));
                if (Boolean.TRUE.equals(done)) {
                    cancelled++;
                }
            } catch (RuntimeException e) {
                log.error("悔拍判定失败，dealId={} 将在下一轮重试", deal.getId(), e);
            }
        }
        if (cancelled > 0) {
            log.info("【拍卖】本批判定悔拍 {} 条", cancelled);
        }
        return cancelled;
    }
}
