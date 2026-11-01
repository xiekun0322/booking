package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionRabbitConfig;
import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.mq.OutboxWriter;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.AuctionTxSupport;
import com.starscreen.auction.support.ChainNotarizeDispatcher;

import lombok.RequiredArgsConstructor;

/**
 * 结算服务：把到点的拍卖关掉，成交的生成成交单，流拍的置流拍。
 *
 * <p><b>成交单与"拍品置 SETTLED"在同一个本地事务里</b>（决策五 A）：
 * 两者同库，所以不存在"拍品成交了但没有成交单"的中间态；
 * 唯一键 {@code uk_deal_item} 兜住重复结算。
 *
 * <p><b>保证金退款不在这里执行</b>：这里只往发件箱写一条"该退了"的事件，
 * 由消费者写标记、worker 原路退回（决策一 C）。原因是退款要调外部通道，
 * 放进来会把关拍事务拖成"一条拍卖卡 60 秒"，进而拖垮整轮扫描。
 *
 * <p><b>边界</b>：悔拍判定属于 {@link DealOverdueSweeper}，不在本类。
 */
@Service
@RequiredArgsConstructor
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);

    private final AuctionItemRepository itemRepository;
    private final DealService dealService;
    private final OutboxWriter outboxWriter;
    private final AuctionAuditWriter auditWriter;
    private final ChainNotarizeDispatcher chainNotarizeDispatcher;

    // ==================== 单条收口 ====================

    /**
     * 关闭一个拍品（成交或流拍）。
     *
     * <p>用条件 UPDATE 决定"谁来关"：{@code affected == 0} 说明别人已经关过了，
     * 直接返回 false。这样扫描重入、一口价并发关拍都不会重复生成成交单。
     *
     * @param requireExpired true = 必须已到结束时间（定时扫描）；false = 立即成交（一口价）
     * @return 本次是否真的由我关闭
     */
    @AuctionTransactional
    public boolean closeOne(Long itemId, boolean requireExpired) {
        AuctionItem item = itemRepository.findById(itemId).orElse(null);
        if (item == null) {
            return false;
        }
        if (item.getStatus() != AuctionStatus.ACTIVE) {
            // 已被取消/已结算：静默跳过，不是错误
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        if (item.getLeaderId() == null) {
            return closeUnsold(item, now);
        }
        return closeSold(item, now, requireExpired);
    }

    /**
     * 修复"开始时间未到但结束时间已过"的滞留拍品。
     *
     * <p>正常发布流程不会产生这种数据（{@code create} 会校验 end > start），
     * 但手工 SQL、历史导入可以造出来。这类拍品的可怕之处是<b>谁都不会处理它</b>：
     * 开拍扫描按 start_time 走不到它，关拍扫描只看 ACTIVE——于是它永远停在待拍，
     * 用户看到"这个拍品一直挂着没开拍"，运维查库也查不出问题。
     */
    @AuctionTransactional
    public boolean expireScheduled(Long itemId) {
        LocalDateTime now = LocalDateTime.now();
        int affected = itemRepository.transitStatus(itemId,
                AuctionStatus.SCHEDULED, AuctionStatus.UNSOLD, now);
        if (affected == 0) {
            return false;
        }
        AuctionItem item = itemRepository.findById(itemId).orElse(null);
        String itemNo = item == null ? "-" : item.getItemNo();
        auditWriter.write("SETTLE", itemId, 0L, "SYSTEM", "AUCTION_EXPIRED_BEFORE_START",
                "itemNo=" + itemNo + "（结束时间已过但从未开拍，置为流拍）", null);
        // 已经报名的用户保证金必须退回，否则钱会永久冻结
        publishRefundTrigger(itemId, null, null, "EXPIRED", now);
        log.warn("【拍卖】拍品未开拍但已过结束时间，置为流拍并触发退款 itemId={} itemNo={}", itemId, itemNo);
        return true;
    }

    /** 扫描一批到点未关的拍品（供 sweeper 逐条在自己的事务里调用）。 */
    @AuctionTransactional(readOnly = true)
    public List<AuctionItem> findExpired(int limit) {
        int batch = Math.min(Math.max(limit, 1), 100);
        return itemRepository.findTop100ByStatusAndEndTimeBeforeOrderByEndTimeAsc(
                        AuctionStatus.ACTIVE, LocalDateTime.now())
                .stream().limit(batch).toList();
    }

    /** 扫描一批"未开拍但已过期"的滞留拍品。 */
    @AuctionTransactional(readOnly = true)
    public List<AuctionItem> findStuckScheduled(int limit) {
        int batch = Math.min(Math.max(limit, 1), 100);
        return itemRepository.findTop100ByStatusAndEndTimeBeforeOrderByEndTimeAsc(
                        AuctionStatus.SCHEDULED, LocalDateTime.now())
                .stream().limit(batch).toList();
    }

    // ==================== 内部 ====================

    private boolean closeSold(AuctionItem item, LocalDateTime now, boolean requireExpired) {
        int affected = itemRepository.settle(item.getId(),
                AuctionStatus.SETTLED, item.getCurrentPrice(), now, requireExpired ? 1 : 0);
        if (affected == 0) {
            return false;
        }

        // 成交单与"拍品置 SETTLED"同事务（决策五 A）
        AuctionDeal deal = dealService.createForItem(item, now);

        auditWriter.write("SETTLE", item.getId(), 0L, "SYSTEM", "AUCTION_SETTLED",
                "winner=" + item.getLeaderId() + ", finalPrice=" + item.getCurrentPrice()
                        + ", dealNo=" + deal.getDealNo(), null);

        // 存证异步：外部网关的 RT 不能进入结算链路
        AuctionTxSupport.afterCommit(() -> chainNotarizeDispatcher.submitSettle(item.getId(),
                item.getItemNo() + "|" + item.getLeaderId() + "|" + item.getCurrentPrice()));

        // 未中标者：拍卖一结束就退，不等中标者付款（决策 D5）。
        // excludeUserId 把中标者排除——他的保证金要等成交款到账后才退（决策 D4）。
        publishRefundTrigger(item.getId(), item.getLeaderId(), null, "SOLD_UNPAID", now);

        log.info("【拍卖】成交 itemId={} itemNo={} winner={} price={} dealNo={}",
                item.getId(), item.getItemNo(), item.getLeaderId(),
                item.getCurrentPrice(), deal.getDealNo());
        return true;
    }

    private boolean closeUnsold(AuctionItem item, LocalDateTime now) {
        int affected = itemRepository.transitStatus(item.getId(),
                AuctionStatus.ACTIVE, AuctionStatus.UNSOLD, now);
        if (affected == 0) {
            return false;
        }

        auditWriter.write("SETTLE", item.getId(), 0L, "SYSTEM", "AUCTION_UNSOLD",
                "itemNo=" + item.getItemNo(), null);

        // 流拍：所有参拍者的保证金立即进入退款流程
        publishRefundTrigger(item.getId(), null, null, "UNSOLD", now);

        log.info("【拍卖】流拍 itemId={} itemNo={}", item.getId(), item.getItemNo());
        return true;
    }

    /**
     * 投递"保证金退款触发"事件（决策一 C 的落点）。
     *
     * <p>结算事务里只写发件箱，不直接改保证金：把"写标记"交给异步消费者，
     * 好处是退款标记与退款执行共用一套幂等与监控，也避免结算事务被大批量 UPDATE 拖长。
     *
     * @param excludeUserId 排除的用户（中标者），null 表示不排除
     * @param includeUserId 只处理该用户，null 表示全部
     * @param reason        SOLD_UNPAID / UNSOLD / EXPIRED
     */
    private void publishRefundTrigger(Long itemId, Long excludeUserId, Long includeUserId,
                                      String reason, LocalDateTime now) {
        AuctionDepositEvent event = AuctionDepositEvent.builder()
                .eventType("DEPOSIT_REFUND_TRIGGER")
                .itemId(itemId)
                .excludeUserId(excludeUserId)
                .includeUserId(includeUserId)
                .reason(reason)
                .occurredAt(now)
                .build();
        outboxWriter.write("AUCTION_ITEM", itemId, "DEPOSIT_REFUND_TRIGGER",
                AuctionRabbitConfig.DEPOSIT_ROUTING_KEY, event);
        log.debug("【拍卖】已写退款触发事件 itemId={} exclude={} reason={}",
                itemId, excludeUserId, reason);
    }
}
