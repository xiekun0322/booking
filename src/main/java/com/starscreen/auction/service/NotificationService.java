package com.starscreen.auction.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.starscreen.auction.config.AuctionTransactional;
import com.starscreen.auction.dto.NotificationDTO;
import com.starscreen.auction.dto.PageResult;
import com.starscreen.auction.entity.AuctionNotification;
import com.starscreen.auction.enums.NotificationType;
import com.starscreen.auction.enums.NotifyDeliveryStatus;
import com.starscreen.auction.notify.RecipientResolver;
import com.starscreen.auction.repository.AuctionNotificationRepository;

import lombok.RequiredArgsConstructor;

/**
 * 通知写入与查询。
 *
 * <p><b>写入与业务同事务</b>（决策 D9 的落地口径）：成交、支付、退款这些动作提交了，
 * 通知就必须在库里；放到 afterCommit 写，进程崩溃就丢，而这几类恰恰是最需要留痕的。
 * 代价是通知插入失败会连带业务回滚——但通知表无外键、无唯一约束，
 * 只有磁盘写满这类极端情况才会失败，此时业务本来也成功不了。
 *
 * <p><b>外发异步</b>：这里只落一条 PENDING 记录，真正的邮件由
 * {@code NotificationEmailWorker} 发送并重试，不让网关 RT 进入业务事务。
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private static final int MAX_PAGE_SIZE = 100;

    private final AuctionNotificationRepository repository;
    private final RecipientResolver recipientResolver;

    // ==================== 写入 ====================

    /**
     * 写一条通知（站内信立即可见，邮件待异步投递）。
     *
     * @param bizType 业务类型 DEAL / DEPOSIT / ITEM
     * @param bizId   业务主键，便于按业务反查
     */
    @AuctionTransactional
    public void notify(Long userId, NotificationType type, String title, String content,
                       String bizType, Long bizId) {
        if (userId == null || type == null) {
            return;
        }
        try {
            doNotify(userId, type, title, content, bizType, bizId);
        } catch (RuntimeException e) {
            // 通知失败绝不能让成交、退款这些主流程失败（决策 D9：不阻塞主流程）。
            // 这里吞掉异常只留 ERROR：用户可以重建站内信，但成交单回滚是不可接受的。
            log.error("拍卖通知写入失败，已忽略 type={} userId={} bizId={}", type, userId, bizId, e);
        }
    }

    private void doNotify(Long userId, NotificationType type, String title, String content,
                          String bizType, Long bizId) {
        String email = recipientResolver.resolveEmail(userId);
        LocalDateTime now = LocalDateTime.now();

        AuctionNotification n = new AuctionNotification();
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(truncate(title, 120));
        n.setContent(truncate(content, 500));
        n.setBizType(bizType);
        n.setBizId(bizId);
        n.setReadFlag(false);
        // 没有邮箱不算失败：站内信已送达，外发直接记为跳过，避免进入重试队列空转
        n.setDeliveryStatus(email == null ? NotifyDeliveryStatus.SKIPPED : NotifyDeliveryStatus.PENDING);
        n.setEmail(email);
        n.setRetryCount(0);
        n.setCreateTime(now);
        n.setUpdateTime(now);

        repository.save(n);
        log.debug("写入拍卖通知 type={} userId={} bizType={} bizId={}", type, userId, bizType, bizId);
    }

    // ==================== 查询 ====================

    @AuctionTransactional(readOnly = true)
    public PageResult<NotificationDTO> myList(Long userId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Page<AuctionNotification> result =
                repository.findByUserIdOrderByIdDesc(userId, PageRequest.of(safePage, safeSize));

        List<NotificationDTO> content = result.getContent().stream().map(this::toDTO).toList();
        // 用 of() 而不是直接 new：它负责把 Spring 的 0 基页码转成对前端友好的 1 基
        return PageResult.of(result, content);
    }

    @AuctionTransactional(readOnly = true)
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadFlag(userId, false);
    }

    // ==================== 已读 ====================

    @AuctionTransactional
    public void markRead(Long id, Long userId) {
        repository.markRead(id, userId, LocalDateTime.now());
    }

    @AuctionTransactional
    public void markAllRead(Long userId) {
        repository.markAllRead(userId, LocalDateTime.now());
    }

    // ==================== 内部 ====================

    private NotificationDTO toDTO(AuctionNotification n) {
        NotificationDTO dto = new NotificationDTO();
        dto.setId(n.getId());
        dto.setType(n.getType());
        dto.setTypeText(NotificationDTO.textOf(n.getType()));
        dto.setTitle(n.getTitle());
        dto.setContent(n.getContent());
        dto.setReadFlag(Boolean.TRUE.equals(n.getReadFlag()));
        dto.setBizType(n.getBizType());
        dto.setBizId(n.getBizId());
        dto.setCreateTime(n.getCreateTime());
        return dto;
    }

    private String truncate(String v, int max) {
        if (v == null) {
            return "";
        }
        return v.length() <= max ? v : v.substring(0, max);
    }
}
