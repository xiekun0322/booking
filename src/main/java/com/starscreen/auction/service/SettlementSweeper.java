package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.starscreen.auction.config.AuctionDataSourceConfig;
import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.entity.AuctionItem;

/**
 * 关拍扫描器。
 *
 * <p><b>每条一个事务（REQUIRES_NEW）</b>：一条拍品结算失败（例如成交单唯一键异常、
 * 审计表写失败）不能把整批标记为 rollback-only。这一点在批事务里用 try/catch 是挡不住的——
 * 异常被 catch 了，但事务已经被标记回滚，提交时照样抛 {@code UnexpectedRollbackException}。
 *
 * <p><b>同时修复"未开拍但已过期"的滞留拍品</b>：正常发布不会产生这种数据，
 * 但手工 SQL / 历史导入可以。这类记录的危害是它停在 SCHEDULED，
 * 开拍扫描按 start_time 走不到、关拍扫描只看 ACTIVE，于是永远悬着。
 */
@Component
@ConditionalOnProperty(prefix = "app.auction", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SettlementSweeper {

    private static final Logger log = LoggerFactory.getLogger(SettlementSweeper.class);

    private final SettlementService settlementService;
    private final AuctionProperties properties;
    private final TransactionTemplate perItemTx;

    public SettlementSweeper(SettlementService settlementService,
                             AuctionProperties properties,
                             @Qualifier(AuctionDataSourceConfig.AUCTION_TX_MANAGER)
                             PlatformTransactionManager auctionTransactionManager) {
        this.settlementService = settlementService;
        this.properties = properties;
        this.perItemTx = new TransactionTemplate(auctionTransactionManager);
        this.perItemTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 定时关拍。间隔默认 5 秒：拍卖结束时间精确到秒，再长用户会看到"已到点但还在拍"。 */
    @Scheduled(fixedDelayString = "${app.auction.settle-interval-ms:5000}")
    public void sweep() {
        closeExpired();
        repairStuckScheduled();
    }

    private void closeExpired() {
        try {
            List<AuctionItem> expired = settlementService.findExpired(properties.getSettleBatchSize());
            int closed = 0;
            for (AuctionItem item : expired) {
                try {
                    Boolean done = perItemTx.execute(s -> settlementService.closeOne(item.getId(), true));
                    if (Boolean.TRUE.equals(done)) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    // 单条失败不影响其它条：本条已随自己的事务回滚，下一轮再试
                    log.error("关拍失败，itemId={} 将在下一轮重试", item.getId(), e);
                }
            }
            if (closed > 0) {
                log.info("【拍卖】本批关拍 {} 条", closed);
            }
        } catch (RuntimeException e) {
            log.error("关拍扫描失败，下一轮重试", e);
        }
    }

    private void repairStuckScheduled() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<AuctionItem> stuck = settlementService.findStuckScheduled(properties.getSettleBatchSize());
            for (AuctionItem item : stuck) {
                try {
                    perItemTx.execute(s -> settlementService.expireScheduled(item.getId()));
                } catch (RuntimeException e) {
                    log.error("滞留拍品修复失败 itemId={}", item.getId(), e);
                }
            }
            if (!stuck.isEmpty()) {
                log.warn("【拍卖】发现 {} 条未开拍但已过结束时间的滞留拍品，已置流拍 checkTime={}",
                        stuck.size(), now);
            }
        } catch (RuntimeException e) {
            log.error("滞留拍品扫描失败，下一轮重试", e);
        }
    }
}
