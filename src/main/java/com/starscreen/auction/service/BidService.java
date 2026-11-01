package com.starscreen.auction.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionProperties;
import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.BidResultDTO;
import com.starscreen.auction.entity.AuctionBid;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.enums.BidOutcome;
import com.starscreen.auction.repository.AuctionBidRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.auction.support.BidderNameResolver;
import com.starscreen.common.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 出价服务。
 *
 * <p><b>整套设计的核心是一句话：出价不能"读-改-写"</b>。
 * 拍卖最热的一行就是 {@code auction_item}，同一秒可能有几十个人出价。
 * 若按"查出来 → 比大小 → 改字段 → 保存"来写，两个人会同时通过校验，
 * 后写的把先写的覆盖掉——用户看到"我出价成功了"但价格没变，或者名次莫名丢失。
 *
 * <p>正确做法是让数据库做裁判：{@code UPDATE ... WHERE current_price < :amount}，
 * 影响 1 行即成功，0 行说明被别人抢先。整个流程没有悲观锁，也不会有锁等待。
 *
 * <p><b>四道关卡</b>（按顺序，越靠前越便宜）：
 * <ol>
 *   <li>幂等键：重复请求直接返回上次结果，不参与竞价；</li>
 *   <li>可出价性：状态、时间、自买自卖、保证金资格；</li>
 *   <li>限流：挡脚本；</li>
 *   <li>条件 UPDATE：并发裁判。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class BidService {

    private static final Logger log = LoggerFactory.getLogger(BidService.class);

    private final AuctionItemRepository itemRepository;
    private final AuctionBidRepository bidRepository;
    private final AuctionAuditWriter auditWriter;
    private final BidderNameResolver bidderNameResolver;
    private final BidRateLimiter rateLimiter;
    private final DepositService depositService;
    private final SettlementService settlementService;
    private final AuctionProperties properties;

    /**
     * 出价。
     *
     * <p><b>被拒绝也返回 200</b>：价格被抢先、未缴保证金都是正常业务结果，
     * 用 4xx 会让前端把"业务提示"和"系统故障"混为一谈。
     * 只有请求本身有问题（参数非法、幂等键重复冲突）才抛 4xx。
     */
    @AuctionTransactional
    public BidResultDTO bid(Long itemId, Long userId, BigDecimal amount,
                            String idempotencyKey, String clientIp) {

        // ① 幂等：先查后做。放在最前面，用户在网络超时后重试时，
        //    即使拍卖已经结束，也能拿回"上次成功"的结果，而不是被告知"拍卖已结束"。
        Optional<AuctionBid> replay = bidRepository.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return replay(replay.get());
        }

        AuctionItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "拍品不存在"));
        LocalDateTime now = LocalDateTime.now();

        // ② 可出价性
        Rejection rejection = checkBiddable(item, userId, now);
        if (rejection != null) {
            return reject(item, userId, amount, idempotencyKey, clientIp, rejection, now);
        }

        // ③ 价格校验（在限流之前：价格不对的请求没必要消耗限流额度）
        BigDecimal increment = item.getBidIncrement() == null ? BigDecimal.ZERO : item.getBidIncrement();
        BigDecimal minimumNext = item.getCurrentPrice().add(increment);
        boolean buyout = item.getBuyoutPrice() != null && amount.compareTo(item.getBuyoutPrice()) >= 0;
        BigDecimal effectiveAmount = buyout ? item.getBuyoutPrice() : amount;

        if (!buyout && amount.compareTo(minimumNext) < 0) {
            return reject(item, userId, amount, idempotencyKey, clientIp,
                    new Rejection(BidOutcome.BELOW_MINIMUM,
                            "出价需不低于 ¥" + minimumNext.toPlainString()), now);
        }
        if (amount.compareTo(minimumNext) < 0 && buyout) {
            // 一口价低于最低加价（定价配置异常）：仍然按一口价成交，但留痕
            log.warn("拍品一口价低于最低加价 itemId={} buyout={} minimumNext={}",
                    itemId, item.getBuyoutPrice(), minimumNext);
        }

        // ④ 限流
        if (!rateLimiter.tryAcquire(userId)) {
            return reject(item, userId, amount, idempotencyKey, clientIp,
                    new Rejection(BidOutcome.RATE_LIMITED, "出价过于频繁，请稍后再试"), now);
        }

        // ⑤ 条件 UPDATE：数据库裁决并发
        String bidderName = bidderNameResolver.resolve(userId);
        int affected = itemRepository.placeBid(itemId, userId, bidderName, effectiveAmount, now);
        if (affected == 0) {
            AuctionItem latest = itemRepository.findById(itemId).orElseThrow();
            BidOutcome outcome = latest.getStatus() == AuctionStatus.ACTIVE
                    ? BidOutcome.BELOW_MINIMUM
                    : BidOutcome.NOT_BIDDABLE;
            String message = outcome == BidOutcome.BELOW_MINIMUM
                    ? "已有更高出价，当前价 ¥" + latest.getCurrentPrice().toPlainString()
                    : "拍卖已结束，出价未被接受";
            return reject(latest, userId, amount, idempotencyKey, clientIp,
                    new Rejection(outcome, message), now);
        }

        // ⑥ 落流水
        AuctionBid saved = saveBid(itemId, userId, bidderName, effectiveAmount,
                idempotencyKey, true, null, clientIp, now);

        // ⑦ 顺延（条件 UPDATE + CAS，两个线程同时顺延只有一个生效）
        AuctionItem fresh = itemRepository.findById(itemId).orElse(item);
        boolean extended = extendIfNeeded(fresh, now);

        auditWriter.write("BID", itemId, userId, "USER",
                buyout ? "BID_BUYOUT" : "BID_ACCEPTED",
                "bidId=" + saved.getId() + ", amount=" + effectiveAmount
                        + ", previousLeader=" + item.getLeaderId()
                        + ", extended=" + extended, clientIp);

        // ⑧ 一口价：同事务内立即关拍。走本地事务而不是消息，
        //    因为"成交"与"生成成交单"必须原子（决策五 A）。
        if (buyout) {
            settlementService.closeOne(itemId, false);
            log.info("【拍卖】一口价成交 itemId={} buyer={} price={}", itemId, userId, effectiveAmount);
        }

        AuctionItem after = itemRepository.findById(itemId).orElse(fresh);
        return BidResultDTO.builder()
                .outcome(buyout ? BidOutcome.BUYOUT : BidOutcome.SUCCESS)
                .accepted(true)
                .message(buyout ? "一口价成交" : (extended ? "出价成功，拍卖已顺延" : "出价成功"))
                .currentPrice(after.getCurrentPrice())
                .minimumNextBid(after.getCurrentPrice().add(increment))
                .leaderId(after.getLeaderId())
                .leaderName(after.getLeaderName())
                .endTime(after.getEndTime())
                .extended(extended)
                .build();
    }

    /**
     * 判断是否可出价。
     *
     * <p><b>顺序很重要</b>：先判断"能不能拍"，再判断"有没有资格"。
     * 反过来的话，一个没缴保证金的用户在一场已经结束的拍卖上会看到
     * "请先缴纳保证金"——他交了钱仍然不能出价，是最容易升级为投诉的体验。
     */
    public Rejection checkBiddable(AuctionItem item, Long userId, LocalDateTime now) {
        if (item.getStatus() != AuctionStatus.ACTIVE) {
            String message = switch (item.getStatus()) {
                case SCHEDULED -> "拍卖尚未开始";
                case SETTLED, UNSOLD, DEFAULTED, CANCELLED -> "拍卖已结束";
                default -> "当前状态不可出价";
            };
            return new Rejection(BidOutcome.NOT_BIDDABLE, message);
        }
        if (item.getStartTime() != null && now.isBefore(item.getStartTime())) {
            return new Rejection(BidOutcome.NOT_BIDDABLE, "拍卖尚未开始");
        }
        if (item.getEndTime() != null && !now.isBefore(item.getEndTime())) {
            return new Rejection(BidOutcome.NOT_BIDDABLE, "拍卖已结束");
        }
        if (item.getSellerId() != null && item.getSellerId().equals(userId)) {
            // 自买自卖是最常见的抬价手法，必须能和"已结束"区分开统计
            return new Rejection(BidOutcome.SELF_BID_FORBIDDEN, "不能竞拍自己发布的标的");
        }
        // 参拍资格：保证金已缴纳（HELD）才允许出价
        if (depositService.required(item) && !depositService.hasQualification(userId, item.getId())) {
            return new Rejection(BidOutcome.DEPOSIT_REQUIRED,
                    "请先缴纳保证金 ¥" + item.getDeposit().toPlainString() + " 后再出价");
        }
        return null;
    }

    // ==================== 内部 ====================

    private boolean extendIfNeeded(AuctionItem item, LocalDateTime now) {
        Integer windowSeconds = item.getAutoExtendSeconds();
        if (windowSeconds == null || windowSeconds <= 0 || item.getEndTime() == null) {
            return false;
        }
        long remaining = Duration.between(now, item.getEndTime()).getSeconds();
        if (remaining > windowSeconds) {
            return false;
        }
        int extendTimes = item.getExtendTimes() == null ? 0 : item.getExtendTimes();
        int maxExtend = properties.getMaxExtendTimes();
        if (maxExtend > 0 && extendTimes >= maxExtend) {
            // 到达上限：不再顺延。这不是异常，是防止"持续出价让拍卖永不结束"的硬闸门
            log.debug("拍品已顺延 {} 次达到上限，本次不再顺延 itemId={}", extendTimes, item.getId());
            return false;
        }

        LocalDateTime expectedEnd = item.getEndTime();
        LocalDateTime newEnd = expectedEnd.plusSeconds(windowSeconds);
        int affected = itemRepository.extendEndTime(item.getId(), expectedEnd, newEnd, now, maxExtend);
        if (affected == 0) {
            // 另一个线程刚顺延过（CAS 失败）：结束时间已经是新的，不需要再推
            return false;
        }
        auditWriter.write("ITEM", item.getId(), 0L, "SYSTEM", "END_TIME_EXTENDED",
                "from=" + expectedEnd + ", to=" + newEnd + ", extendTimes=" + (extendTimes + 1), null);
        log.info("【拍卖】剩余不足 {} 秒出价，结束时间顺延至 {} itemId={}",
                windowSeconds, newEnd, item.getId());
        return true;
    }

    private BidResultDTO reject(AuctionItem item, Long userId, BigDecimal amount,
                                String idempotencyKey, String clientIp,
                                Rejection rejection, LocalDateTime now) {
        // 被拒也落流水：纠纷时"我明明出过价"只能靠这张表自证；
        // 同时它是调整加价规则的输入（谁、在什么价位、被拒了多少次）
        saveBid(item.getId(), userId, bidderNameResolver.resolve(userId), amount,
                idempotencyKey, false, rejection.outcome().name(), clientIp, now);

        BigDecimal increment = item.getBidIncrement() == null ? BigDecimal.ZERO : item.getBidIncrement();
        log.info("【拍卖】出价被拒 itemId={} userId={} amount={} reason={}",
                item.getId(), userId, amount, rejection.outcome());
        return BidResultDTO.rejected(rejection.outcome(), rejection.message(),
                item.getCurrentPrice(), item.getCurrentPrice().add(increment));
    }

    private AuctionBid saveBid(Long itemId, Long userId, String bidderName, BigDecimal amount,
                               String idempotencyKey, boolean accepted, String rejectedReason,
                               String clientIp, LocalDateTime now) {
        AuctionBid bid = new AuctionBid();
        bid.setItemId(itemId);
        bid.setBidderId(userId);
        bid.setBidderName(bidderName);
        bid.setAmount(amount);
        bid.setIdempotencyKey(idempotencyKey);
        bid.setAccepted(accepted);
        bid.setRejectedReason(rejectedReason);
        bid.setBidTime(now);
        bid.setClientIp(clientIp);
        try {
            return bidRepository.save(bid);
        } catch (DataIntegrityViolationException e) {
            // 并发同键：另一方已经写入，本次按重复提交处理
            return bidRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> new BusinessException(409, "重复提交，请刷新后重试"));
        }
    }

    /** 幂等重放：返回首次结果，不重复加价。 */
    private BidResultDTO replay(AuctionBid bid) {
        AuctionItem item = itemRepository.findById(bid.getItemId()).orElse(null);
        BigDecimal current = item == null ? bid.getAmount() : item.getCurrentPrice();
        BigDecimal increment = item == null || item.getBidIncrement() == null
                ? BigDecimal.ZERO : item.getBidIncrement();
        BidOutcome outcome = Boolean.TRUE.equals(bid.getAccepted())
                ? BidOutcome.SUCCESS
                : parseOutcome(bid.getRejectedReason());
        return BidResultDTO.builder()
                .outcome(outcome)
                .accepted(Boolean.TRUE.equals(bid.getAccepted()))
                .message(Boolean.TRUE.equals(bid.getAccepted())
                        ? "重复提交，已返回首次出价结果"
                        : "重复提交，首次出价已被拒绝")
                .currentPrice(current)
                .minimumNextBid(current.add(increment))
                .leaderId(item == null ? null : item.getLeaderId())
                .leaderName(item == null ? null : item.getLeaderName())
                .endTime(item == null ? null : item.getEndTime())
                .extended(false)
                .build();
    }

    private BidOutcome parseOutcome(String raw) {
        if (raw == null) {
            return BidOutcome.NOT_BIDDABLE;
        }
        try {
            return BidOutcome.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return BidOutcome.NOT_BIDDABLE;
        }
    }

    /** 可出价性判定结果。用 record 而不是抛异常：这是高频的正常分支，异常栈太贵。 */
    public record Rejection(BidOutcome outcome, String message) {
    }
}
