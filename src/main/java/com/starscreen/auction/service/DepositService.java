package com.starscreen.auction.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.AuctionDepositEvent;
import com.starscreen.auction.dto.DepositDTO;
import com.starscreen.auction.dto.PayTicketDTO;
import com.starscreen.auction.entity.AuctionDeposit;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;
import com.starscreen.auction.enums.DepositStatus;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.pay.MockPayService;
import com.starscreen.auction.repository.AuctionDepositRepository;
import com.starscreen.auction.repository.AuctionItemRepository;
import com.starscreen.auction.support.AuctionAuditWriter;
import com.starscreen.common.BusinessException;

import lombok.RequiredArgsConstructor;

/**
 * 保证金业务：报名缴费、资格校验、退款触发、悔拍罚没。
 *
 * <p><b>状态机的唯一权威在数据库约束上</b>：
 * <ul>
 *   <li>{@code uk_deposit(user_id, item_id)} 保证一人一标的一条记录，重复报名不会扣两次；</li>
 *   <li>所有流转都是条件 UPDATE，重复调用影响 0 行；</li>
 *   <li>"有没有资格"只认 {@code status='HELD'}，不认"是否报名过"。</li>
 * </ul>
 *
 * <p><b>为什么不建独立的退款任务表</b>：退款所需的一切（触发时间、重试次数、下次重试、
 * 错误信息）都是保证金记录自身的属性，拆表只会多一次 join 和一次状态同步。
 * 退款 worker 直接扫 {@code HELD + refund_trigger_at <= now} 即可。
 */
@Service
@RequiredArgsConstructor
public class DepositService {

    private static final Logger log = LoggerFactory.getLogger(DepositService.class);

    private final AuctionItemRepository itemRepository;
    private final AuctionDepositRepository depositRepository;
    private final MockPayService payService;
    private final AuctionAuditWriter auditWriter;

    /**
     * 报名参拍：创建/复用保证金记录并下单。
     *
     * <p>与出价写在同一个事务域（拍卖库），但<b>不锁拍品行</b>：
     * 报名是高频写、出价是热点写，两者抢同一行会互相拖慢。
     */
    @AuctionTransactional
    public PayTicketDTO enroll(Long itemId, Long userId) {
        AuctionItem item = itemRepository.findById(itemId)
                .orElseThrow(() -> new BusinessException(404, "拍品不存在"));

        LocalDateTime now = LocalDateTime.now();
        if (item.getStatus() != AuctionStatus.SCHEDULED && item.getStatus() != AuctionStatus.ACTIVE) {
            throw new BusinessException(400, "当前状态不可报名：" + item.getStatus());
        }
        if (item.getStatus() == AuctionStatus.ACTIVE
                && item.getEndTime() != null && !now.isBefore(item.getEndTime())) {
            throw new BusinessException(400, "拍卖已结束，无法报名");
        }

        BigDecimal amount = item.getDeposit();
        if (amount == null || amount.signum() <= 0) {
            throw new BusinessException(400, "该拍品未设置保证金，暂不支持报名");
        }
        if (item.getSellerId() != null && item.getSellerId().equals(userId)) {
            throw new BusinessException(400, "不能报名自己发布的拍品");
        }

        Optional<AuctionDeposit> existing = depositRepository.findByUserIdAndItemId(userId, itemId);
        AuctionDeposit deposit;
        if (existing.isPresent()) {
            deposit = existing.get();
            if (deposit.getStatus() == DepositStatus.HELD) {
                throw new BusinessException(409, "已缴纳保证金，无需重复报名");
            }
            if (deposit.getStatus() == DepositStatus.REFUNDING
                    || deposit.getStatus() == DepositStatus.REFUNDED
                    || deposit.getStatus() == DepositStatus.REFUND_DEAD
                    || deposit.getStatus() == DepositStatus.FORFEITED) {
                // 已进入资金退出流程的记录不允许复活：复活会让同一笔钱被退两次或罚两次
                throw new BusinessException(409, "保证金已进入"
                        + deposit.getStatus() + "流程，不能重新报名");
            }
            if (deposit.getStatus() == DepositStatus.PENDING_PAY) {
                if (deposit.getPayNo() != null) {
                    // 已有待支付单：把原来的支付凭证再给一次，不重复下单
                    return payService.ticket(deposit.getPayNo());
                }
                // 记录存在但没有支付单（下单前进程中断）：复用该记录继续往下走
            } else {
                // PAY_FAILED / CLOSED：原地复活，复用同一条记录（唯一键不允许新建）
                int reopened = depositRepository.reopen(deposit.getId(), now);
                if (reopened == 0) {
                    throw new BusinessException(409, "保证金状态已变化，请刷新后重试");
                }
                deposit.setStatus(DepositStatus.PENDING_PAY);
            }
        } else {
            deposit = new AuctionDeposit();
            deposit.setUserId(userId);
            deposit.setItemId(itemId);
            deposit.setAmount(amount);
            deposit.setStatus(DepositStatus.PENDING_PAY);
            deposit.setRetryCount(0);
            deposit.setCreateTime(now);
            deposit.setUpdateTime(now);
            depositRepository.save(deposit);
        }

        PayTicketDTO ticket = payService.create(PayBizType.DEPOSIT, deposit.getId(), userId, deposit.getAmount());
        auditWriter.write("DEPOSIT", deposit.getId(), userId, "USER", "DEPOSIT_ENROLL",
                "itemId=" + itemId + ", amount=" + deposit.getAmount() + ", payNo=" + ticket.getPayNo(), null);
        log.info("【保证金】报名成功 depositId={} userId={} itemId={} amount={} payNo={}",
                deposit.getId(), userId, itemId, deposit.getAmount(), ticket.getPayNo());
        return ticket;
    }

    /** 重新发起支付（支付失败 / 取消后）。 */
    @AuctionTransactional
    public PayTicketDTO retryPay(Long depositId, Long userId) {
        AuctionDeposit deposit = depositRepository.findById(depositId)
                .orElseThrow(() -> new BusinessException(404, "保证金记录不存在"));
        if (!deposit.getUserId().equals(userId)) {
            throw new BusinessException(403, "无权操作他人的保证金");
        }
        if (deposit.getStatus() == DepositStatus.HELD) {
            throw new BusinessException(409, "保证金已缴纳，无需重复支付");
        }
        if (deposit.getStatus() != DepositStatus.PAY_FAILED && deposit.getStatus() != DepositStatus.CLOSED) {
            throw new BusinessException(409, "当前状态不可重新支付：" + deposit.getStatus());
        }
        depositRepository.reopen(deposit.getId(), LocalDateTime.now());
        return payService.create(PayBizType.DEPOSIT, deposit.getId(), userId, deposit.getAmount());
    }

    /** 参拍资格：只有 HELD 才算。 */
    @AuctionTransactional(readOnly = true)
    public boolean hasQualification(Long userId, Long itemId) {
        return depositRepository.existsByUserIdAndItemIdAndStatus(userId, itemId, DepositStatus.HELD);
    }

    /** 拍品是否要求保证金（金额 > 0）。 */
    public boolean required(AuctionItem item) {
        return item.getDeposit() != null && item.getDeposit().signum() > 0;
    }

    /** 我的保证金列表（批量回填拍品标题，避免 N+1）。 */
    @AuctionTransactional(readOnly = true)
    public List<DepositDTO> myDeposits(Long userId) {
        List<AuctionDeposit> deposits = depositRepository.findByUserIdOrderByIdDesc(userId);
        if (deposits.isEmpty()) {
            return List.of();
        }
        List<Long> itemIds = deposits.stream().map(AuctionDeposit::getItemId).distinct().toList();
        Map<Long, String> titles = itemRepository.findAllById(itemIds).stream()
                .collect(Collectors.toMap(AuctionItem::getId,
                        item -> item.getTitle() == null ? "" : item.getTitle(),
                        (a, b) -> a));
        Function<Long, String> titleOf = id -> titles.getOrDefault(id, "");
        return deposits.stream()
                .map(d -> DepositDTO.of(d, titleOf.apply(d.getItemId())))
                .toList();
    }

    /**
     * 写退款触发时间（消费者调用）。
     *
     * <p>这是决策一 C / 决策二 B 的落点：结算/付款事务只负责"写一条 outbox"，
     * 真正的标记由这里异步完成。条件 UPDATE 保证重复消息不把时间往后推。
     */
    @AuctionTransactional
    public int onRefundTrigger(AuctionDepositEvent event) {
        if (event.getItemId() == null) {
            log.warn("退款触发事件缺少 itemId，忽略");
            return 0;
        }
        int affected = depositRepository.markRefundTrigger(
                event.getItemId(), event.getExcludeUserId(), event.getIncludeUserId(), LocalDateTime.now());
        log.info("【保证金】退款触发已标记 itemId={} exclude={} include={} reason={} affected={}",
                event.getItemId(), event.getExcludeUserId(), event.getIncludeUserId(),
                event.getReason(), affected);
        return affected;
    }

    /** 悔拍罚没（消费者调用）。 */
    @AuctionTransactional
    public int onForfeit(AuctionDepositEvent event) {
        if (event.getItemId() == null || event.getIncludeUserId() == null) {
            log.warn("罚没事件参数不完整，忽略 event={}", event);
            return 0;
        }
        int affected = depositRepository.forfeit(event.getItemId(), event.getIncludeUserId(), LocalDateTime.now());
        if (affected > 0) {
            auditWriter.write("DEPOSIT", event.getDealId(), event.getIncludeUserId(), "SYSTEM", "DEPOSIT_FORFEITED",
                    "itemId=" + event.getItemId() + ", dealId=" + event.getDealId(), null);
        }
        log.info("【保证金】悔拍罚没 itemId={} userId={} dealId={} affected={}",
                event.getItemId(), event.getIncludeUserId(), event.getDealId(), affected);
        return affected;
    }
}
