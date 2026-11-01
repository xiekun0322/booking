package com.starscreen.auction.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.AuctionItemDTO;
import com.starscreen.auction.dto.BidDTO;
import com.starscreen.auction.dto.CreateAuctionItemRequest;
import com.starscreen.auction.dto.PageResult;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.repository.AuctionBidRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.AuctionIdGenerator;
import com.starscreen.common.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 拍品服务：发布、列表、详情、开拍扫描、取消。
 *
 * <p><b>开拍为什么只能靠扫描</b>：拍品的开拍时间是"将来某个时刻"，没有任何事件会在这个时刻发生。
 * 所以必须有一个定时任务持续把 {@code SCHEDULED + start_time <= now} 推进到 {@code ACTIVE}。
 * 这不是设计缺陷，而是"未来时间点"这类业务的事实约束（与悔拍判定同理）。
 *
 * <p><b>状态推进一律用条件 UPDATE</b>：见 {@link AuctionItemRepository} 的说明。
 */
@Service
@RequiredArgsConstructor
public class AuctionService {

    private static final Logger log = LoggerFactory.getLogger(AuctionService.class);

    /** 列表页单页上限：防止有人传 size=100000 把库拖死。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final AuctionItemRepository itemRepository;
    private final AuctionBidRepository bidRepository;
    private final AuctionIdGenerator idGenerator;
    private final AuctionAuditWriter auditWriter;
    private final DepositPolicy depositPolicy;
    private final AuctionProperties properties;

    // ==================== 发布 ====================

    /**
     * 发布拍品（落库即 SCHEDULED，等待开拍扫描）。
     *
     * <p><b>保证金在这里一次性算好并固化</b>（决策四 C）：之后报名、扣划、退款全部只读
     * {@code item.deposit} 这个快照，永不重算。否则调整比例会让同一场拍卖里
     * 早报名与晚报名的人金额不同，退款时无法对账。
     */
    @AuctionTransactional
    public AuctionItemDTO create(CreateAuctionItemRequest req, Long sellerId, String sellerName) {
        LocalDateTime now = LocalDateTime.now();
        validateCreate(req, now);

        AuctionItem item = new AuctionItem();
        item.setItemNo(idGenerator.nextItemNo());
        item.setTitle(req.getTitle().trim());
        item.setDescription(req.getDescription());
        item.setCategory(req.getCategory());
        item.setSellerId(sellerId);
        item.setSellerName(sellerName);
        item.setStartPrice(req.getStartPrice());
        item.setCurrentPrice(req.getStartPrice());
        item.setBuyoutPrice(req.getBuyoutPrice());
        item.setBidIncrement(req.getBidIncrement());
        item.setDeposit(depositPolicy.calculate(req.getStartPrice()));
        item.setStatus(AuctionStatus.SCHEDULED);
        item.setStartTime(req.getStartTime());
        item.setEndTime(req.getEndTime());
        item.setAutoExtendSeconds(req.getAutoExtendSeconds() == null ? 0 : req.getAutoExtendSeconds());
        item.setExtendTimes(0);
        item.setCreateTime(now);
        item.setUpdateTime(now);
        itemRepository.save(item);

        auditWriter.write("ITEM", item.getId(), sellerId, "USER", "ITEM_PUBLISHED",
                "itemNo=" + item.getItemNo() + ", start=" + item.getStartTime()
                        + ", end=" + item.getEndTime() + ", deposit=" + item.getDeposit(), null);
        log.info("【拍卖】拍品已发布 itemId={} itemNo={} start={} end={} deposit={}",
                item.getId(), item.getItemNo(), item.getStartTime(), item.getEndTime(), item.getDeposit());
        return detail(item.getId());
    }

    private void validateCreate(CreateAuctionItemRequest req, LocalDateTime now) {
        if (!req.getStartTime().isAfter(now.minusSeconds(60))) {
            // 允许 1 分钟容差：客户端与服务端时钟有偏差时，不该因为几秒就拒绝发布
            throw new BusinessException(400, "开始时间必须晚于当前时间");
        }
        if (!req.getEndTime().isAfter(req.getStartTime())) {
            throw new BusinessException(400, "结束时间必须晚于开始时间");
        }
        if (req.getBuyoutPrice() != null && req.getBuyoutPrice().compareTo(req.getStartPrice()) < 0) {
            throw new BusinessException(400, "一口价不能低于起拍价");
        }
    }

    // ==================== 开拍扫描 ====================

    /**
     * 把到点的拍品推进为竞拍中。
     *
     * @return 本批实际开拍的条数
     */
    @AuctionTransactional
    public int startDueAuctions() {
        LocalDateTime now = LocalDateTime.now();
        int batch = Math.max(1, properties.getStartBatchSize());
        List<AuctionItem> due = itemRepository
                .findTop100ByStatusAndStartTimeBeforeOrderByStartTimeAsc(AuctionStatus.SCHEDULED, now);

        int started = 0;
        for (AuctionItem item : due) {
            if (started >= batch) {
                break;
            }
            int affected = itemRepository.transitStatus(item.getId(),
                    AuctionStatus.SCHEDULED, AuctionStatus.ACTIVE, now);
            if (affected == 0) {
                // 被用户取消或另一实例抢先开拍：正常竞争，不是错误
                continue;
            }
            started++;
            auditWriter.write("ITEM", item.getId(), 0L, "SYSTEM", "AUCTION_STARTED",
                    "itemNo=" + item.getItemNo() + ", startPrice=" + item.getCurrentPrice(), null);
        }
        if (started > 0) {
            log.info("【拍卖】{} 个拍品已开拍", started);
        }
        return started;
    }

    // ==================== 查询 ====================

    /**
     * 拍品列表。
     *
     * <p><b>排序随状态切换</b>：竞拍中按结束时间升序（快结束的排前面，用户关心）；
     * 待拍按开始时间升序（快开拍的排前面）。早期统一按 end_time 排序，
     * 待拍列表第一条不是最先开拍的，用户点进去发现还有几小时才开始。
     */
    @AuctionTransactional(readOnly = true)
    public PageResult<AuctionItemDTO> page(AuctionStatus status, int page, int size) {
        AuctionStatus target = status == null ? AuctionStatus.ACTIVE : status;
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);

        Sort sort = target == AuctionStatus.SCHEDULED
                ? Sort.by(Sort.Direction.ASC, "startTime")
                : Sort.by(Sort.Direction.ASC, "endTime");
        Pageable pageable = PageRequest.of(safePage, safeSize, sort);
        Page<AuctionItem> result = itemRepository.findByStatus(target, pageable);

        List<AuctionItem> items = result.getContent();
        Map<Long, Long> counts = countBids(items);
        LocalDateTime now = LocalDateTime.now();
        List<AuctionItemDTO> content = items.stream()
                .map(item -> AuctionItemDTO.of(item,
                        counts.getOrDefault(item.getId(), 0L),
                        remainingSeconds(item, now)))
                .toList();
        return PageResult.of(result, content);
    }

    @AuctionTransactional(readOnly = true)
    public AuctionItemDTO detail(Long itemId) {
        AuctionItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "拍品不存在"));
        long bidCount = bidRepository.countByItemIdAndAcceptedTrue(itemId);
        return AuctionItemDTO.of(item, bidCount, remainingSeconds(item, LocalDateTime.now()));
    }

    @AuctionTransactional(readOnly = true)
    public PageResult<BidDTO> bidHistory(Long itemId, int page, int size) {
        if (!itemRepository.existsById(itemId)) {
            throw new BusinessException(404, "拍品不存在");
        }
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
        Page<com.starscreen.auction.entity.AuctionBid> result =
                bidRepository.findByItemIdOrderByIdDesc(itemId, pageable);
        return PageResult.of(result, result.getContent().stream().map(BidDTO::of).toList());
    }

    // ==================== 取消 ====================

    /**
     * 取消拍品（仅开拍前）。
     *
     * <p>竞拍中不允许取消：已经有人出价、有人缴了保证金，此时撤回会引发
     * "我的保证金怎么办"的连锁问题，应由运营走客服流程而不是一个接口。
     */
    @AuctionTransactional
    public void cancel(Long itemId, Long userId, boolean admin) {
        AuctionItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "拍品不存在"));
        if (!admin && (item.getSellerId() == null || !item.getSellerId().equals(userId))) {
            throw new BusinessException(403, "只有卖家可以取消该拍品");
        }
        int affected = itemRepository.transitStatus(itemId,
                AuctionStatus.SCHEDULED, AuctionStatus.CANCELLED, LocalDateTime.now());
        if (affected == 0) {
            throw new BusinessException(409, "当前状态不可取消（仅开拍前可取消）：" + item.getStatus());
        }
        auditWriter.write("ITEM", itemId, userId, admin ? "ADMIN" : "USER", "ITEM_CANCELLED",
                "itemNo=" + item.getItemNo(), null);
        log.info("【拍卖】拍品已取消 itemId={} operator={}", itemId, userId);
    }

    // ==================== 内部 ====================

    private Map<Long, Long> countBids(List<AuctionItem> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = items.stream().map(AuctionItem::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : bidRepository.countAcceptedByItemIds(ids)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * 剩余秒数：待拍算"距开拍"，竞拍中算"距结束"。
     *
     * <p>语义随状态变化是有意为之——待拍卡片若显示"距结束 9 小时"，
     * 用户会以为正在拍却点不出价。
     */
    private long remainingSeconds(AuctionItem item, LocalDateTime now) {
        LocalDateTime target = switch (item.getStatus()) {
            case SCHEDULED -> item.getStartTime();
            case ACTIVE -> item.getEndTime();
            default -> null;
        };
        if (target == null) {
            return 0L;
        }
        long seconds = Duration.between(now, target).getSeconds();
        return Math.max(seconds, 0L);
    }

    /** 供管理端/测试判断：拍品当前最低可出价。 */
    public BigDecimal minimumNextBid(AuctionItem item) {
        BigDecimal increment = item.getBidIncrement() == null ? BigDecimal.ZERO : item.getBidIncrement();
        return item.getCurrentPrice().add(increment);
    }
}
