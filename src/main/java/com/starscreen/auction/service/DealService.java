package com.starscreen.auction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.config.AuctionRabbitConfig;
import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.dto.DealDTO;
import com.starscreen.auction.dto.PayTicketDTO;
import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.entity.AuctionDeposit;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.DealStatus;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.mq.OutboxWriter;
import com.starscreen.auction.pay.MockPayService;
import com.starscreen.auction.repository.AuctionDealRepository;
import com.starscreen.auction.repository.AuctionDepositRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.AuctionIdGenerator;
import com.starscreen.auction.support.DealClock;
import com.starscreen.common.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 成交单业务：关拍生成、支付、悔拍取消、查询。
 *
 * <p><b>生成时机</b>：由 {@link SettlementService#closeSold} 在关拍的<b>同一个事务</b>里调用。
 * 拍品置 SETTLED 与成交单落库要么都成功、要么都失败，不存在"拍品成交了但没有成交单"。
 *
 * <p><b>幂等</b>：唯一键 {@code uk_deal_item(item_id)} 是最后一道防线。
 * 重复结算（扫描重入、一口价与到点关拍并发）时第二次 insert 撞键，
 * 这里捕获后返回已存在的成交单，调用方无需关心是第几次结算。
 */
@Service
@RequiredArgsConstructor
public class DealService {

    private static final Logger log = LoggerFactory.getLogger(DealService.class);

    private final AuctionDealRepository dealRepository;
    private final AuctionDepositRepository depositRepository;
    private final AuctionItemRepository itemRepository;
    private final MockPayService payService;
    private final OutboxWriter outboxWriter;
    private final AuctionIdGenerator idGenerator;
    private final AuctionAuditWriter auditWriter;
    private final AuctionProperties properties;

    /**
     * 为成交的拍品生成成交单（同事务、幂等）。
     *
     * @param item 已置为 SETTLED 的拍品（须已有 leaderId）
     * @param now  结算时间
     */
    @AuctionTransactional
    public AuctionDeal createForItem(AuctionItem item, LocalDateTime now) {
        Optional<AuctionDeal> existing = dealRepository.findByItemId(item.getId());
        if (existing.isPresent()) {
            log.info("成交单已存在，跳过生成 itemId={} dealId={}", item.getId(), existing.get().getId());
            return existing.get();
        }

        AuctionDeal deal = new AuctionDeal();
        deal.setDealNo(idGenerator.nextBizNo("AD"));
        deal.setItemId(item.getId());
        deal.setItemNo(item.getItemNo());
        deal.setBuyerId(item.getLeaderId());
        deal.setBuyerName(item.getLeaderName());
        deal.setDealPrice(item.getCurrentPrice());
        deal.setDepositAmount(resolveDepositAmount(item));
        deal.setStatus(DealStatus.PENDING_PAYMENT);
        deal.setPayDeadline(now.plusHours(Math.max(1, properties.getDeal().getPayDeadlineHours())));
        deal.setCreateTime(now);
        deal.setUpdateTime(now);

        try {
            // saveAndFlush：让唯一键冲突在本方法内抛出，否则要到事务提交时才暴露，
            // 那时已经无法在同一个事务里改为"返回已有成交单"。
            dealRepository.saveAndFlush(deal);
        } catch (DataIntegrityViolationException e) {
            AuctionDeal concurrent = dealRepository.findByItemId(item.getId()).orElse(null);
            if (concurrent != null) {
                log.info("并发生成成交单，采用已存在的记录 itemId={} dealId={}", item.getId(), concurrent.getId());
                return concurrent;
            }
            throw e;
        }

        auditWriter.write("DEAL", deal.getId(), item.getLeaderId(), "SYSTEM", "DEAL_CREATED",
                "dealNo=" + deal.getDealNo() + ", price=" + deal.getDealPrice()
                        + ", deadline=" + deal.getPayDeadline(), null);
        log.info("【拍卖】成交单已生成 dealId={} dealNo={} itemId={} price={} deadline={}",
                deal.getId(), deal.getDealNo(), item.getId(), deal.getDealPrice(), deal.getPayDeadline());
        return deal;
    }

    /**
     * 发起成交款支付。
     *
     * <p>校验顺序：归属 → 状态 → 是否超时。超时与否用 {@link DealClock#payable} 判断，
     * 与 sweeper 用同一套逻辑；即便这里放过了已超时的单子，
     * 回调落库时 {@code markPaid} 的条件 UPDATE 仍会挡住（它会先被 sweeper 置为 CANCELLED）。
     */
    @AuctionTransactional
    public PayTicketDTO pay(Long dealId, Long userId) {
        AuctionDeal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new BusinessException(404, "成交单不存在"));
        if (!deal.getBuyerId().equals(userId)) {
            throw new BusinessException(403, "只有中标人可以支付该成交单");
        }
        LocalDateTime now = LocalDateTime.now();
        if (deal.getStatus() == DealStatus.PAID || deal.getStatus() == DealStatus.COMPLETED) {
            throw new BusinessException(409, "该成交单已支付");
        }
        if (deal.getStatus() == DealStatus.CANCELLED) {
            throw new BusinessException(409, "该成交单已取消（" + deal.getCancelReason() + "），无法支付");
        }
        if (!DealClock.payable(deal, now)) {
            throw new BusinessException(409, "已超过支付截止时间，成交单将被判定为悔拍");
        }
        return payService.create(PayBizType.DEAL, deal.getId(), userId, deal.getDealPrice());
    }

    /**
     * 超时悔拍（sweeper 调用）：CANCELLED + 写罚没事件。
     *
     * <p>罚没不在这里直接改保证金：保持"一条链路一个入口"，与退款一样走发件箱，
     * 由消费者把 {@code HELD → FORFEITED}。
     */
    @AuctionTransactional
    public boolean cancelOverdue(Long dealId) {
        LocalDateTime now = LocalDateTime.now();
        int affected = dealRepository.cancelOverdue(dealId, now);
        if (affected == 0) {
            return false;
        }
        AuctionDeal deal = dealRepository.findById(dealId).orElse(null);
        if (deal == null) {
            return false;
        }

        AuctionDepositEvent event = AuctionDepositEvent.builder()
                .eventType("DEPOSIT_FORFEIT")
                .itemId(deal.getItemId())
                .includeUserId(deal.getBuyerId())
                .dealId(deal.getId())
                .reason("PAY_TIMEOUT")
                .occurredAt(now)
                .build();
        outboxWriter.write("AUCTION_DEAL", deal.getId(), "DEPOSIT_FORFEIT",
                AuctionRabbitConfig.DEPOSIT_ROUTING_KEY, event);

        auditWriter.write("DEAL", deal.getId(), deal.getBuyerId(), "SYSTEM", "DEAL_CANCEL_OVERDUE",
                "dealNo=" + deal.getDealNo() + ", deadline=" + deal.getPayDeadline(), null);
        log.warn("【拍卖】成交单超时未付，判定悔拍 dealId={} dealNo={} buyer={}",
                deal.getId(), deal.getDealNo(), deal.getBuyerId());
        return true;
    }

    /** 我的成交列表。 */
    @AuctionTransactional(readOnly = true)
    public List<DealDTO> myDeals(Long userId) {
        List<AuctionDeal> deals = dealRepository.findByBuyerIdOrderByIdDesc(userId,
                PageRequest.of(0, 100)).getContent();
        return enrich(deals);
    }

    /** 按拍品查成交单（拍品详情页展示用）。 */
    @AuctionTransactional(readOnly = true)
    public DealDTO byItem(Long itemId) {
        return dealRepository.findByItemId(itemId)
                .map(d -> enrich(List.of(d)).get(0))
                .orElseThrow(() -> new BusinessException(404, "该拍品暂无成交单"));
    }

    @AuctionTransactional(readOnly = true)
    public DealDTO detail(Long dealId) {
        AuctionDeal deal = dealRepository.findById(dealId)
                .orElseThrow(() -> new BusinessException(404, "成交单不存在"));
        return enrich(List.of(deal)).get(0);
    }

    // ==================== 内部 ====================

    private List<DealDTO> enrich(List<AuctionDeal> deals) {
        if (deals.isEmpty()) {
            return List.of();
        }
        LocalDateTime now = LocalDateTime.now();
        List<Long> itemIds = deals.stream().map(AuctionDeal::getItemId).distinct().toList();
        Map<Long, String> titles = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(AuctionItem::getId,
                        i -> i.getTitle() == null ? "" : i.getTitle(), (a, b) -> a));

        return deals.stream().map(d -> {
            boolean overdue = DealClock.isOverdue(d, now);
            return DealDTO.builder()
                    .id(d.getId())
                    .dealNo(d.getDealNo())
                    .itemId(d.getItemId())
                    .itemTitle(titles.getOrDefault(d.getItemId(), ""))
                    .dealPrice(d.getDealPrice())
                    .depositAmount(d.getDepositAmount())
                    .status(d.getStatus())
                    .payDeadline(d.getPayDeadline())
                    .payTime(d.getPayTime())
                    .remainingSeconds(DealClock.remainingSeconds(d, now))
                    .overdue(overdue)
                    .cancelReason(d.getCancelReason())
                    .actionHint(hintOf(d, overdue))
                    .build();
        }).toList();
    }

    private String hintOf(AuctionDeal deal, boolean overdue) {
        if (deal.getStatus() == DealStatus.PENDING_PAYMENT) {
            return overdue ? "OVERDUE" : "PAY";
        }
        return "DONE";
    }

    /**
     * 中标者实缴保证金：优先取保证金快照，缺失时退回拍品定价。
     *
     * <p>只做记录用途：真正退多少以 {@code auction_deposit.amount} 为准，
     * 这里的值用于展示"这单押了多少"，不参与退款计算。
     */
    private BigDecimal resolveDepositAmount(AuctionItem item) {
        if (item.getLeaderId() == null) {
            return BigDecimal.ZERO;
        }
        return depositRepository.findByUserIdAndItemId(item.getLeaderId(), item.getId())
                .map(AuctionDeposit::getAmount)
                .orElse(item.getDeposit() == null ? BigDecimal.ZERO : item.getDeposit());
    }
}
