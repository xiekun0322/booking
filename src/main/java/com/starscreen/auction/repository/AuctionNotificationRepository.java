package com.starscreen.auction.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionNotification;
import com.starscreen.auction.enums.NotifyDeliveryStatus;

/**
 * 通知数据访问。
 *
 * <p>投递状态推进一律条件 UPDATE：worker 与"人工重放"可能同时碰同一行，
 * 先读再写会把已发送的记录改回待发送，导致重复发信。
 */
public interface AuctionNotificationRepository extends JpaRepository<AuctionNotification, Long> {

    Page<AuctionNotification> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    long countByUserIdAndReadFlag(Long userId, Boolean readFlag);

    /** 监控：按投递状态计数（DEAD 需要人工重发）。 */
    long countByDeliveryStatus(NotifyDeliveryStatus status);

    /**
     * 待投递扫描：PENDING（从未尝试）或 FAILED（试过但失败），且已到可发送时间。
     *
     * <p>{@code next_retry_at IS NULL} 必须放行：第一次待发的记录没有这个值，
     * 漏掉它会让新通知永远发不出去——这是"退避字段"最容易踩的坑。
     */
    @Query("""
            SELECT n FROM AuctionNotification n
             WHERE n.deliveryStatus IN :statuses
               AND (n.nextRetryAt IS NULL OR n.nextRetryAt <= :now)
             ORDER BY n.id ASC
            """)
    List<AuctionNotification> findDeliverable(@Param("statuses") java.util.Collection<NotifyDeliveryStatus> statuses,
                                              @Param("now") LocalDateTime now,
                                              Pageable pageable);

    /** 标记已读：带 user_id 条件，避免把别人的通知标成已读。 */
    @Modifying
    @Query("UPDATE AuctionNotification n SET n.readFlag = true, n.updateTime = :now "
            + "WHERE n.id = :id AND n.userId = :userId AND n.readFlag = false")
    int markRead(@Param("id") Long id, @Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Modifying
    @Query("UPDATE AuctionNotification n SET n.readFlag = true, n.updateTime = :now "
            + "WHERE n.userId = :userId AND n.readFlag = false")
    int markAllRead(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 投递成功：只有当前仍是 PENDING/FAILED 才置 SENT。
     *
     * <p>条件里带上状态判断，是为了让"worker 重入 + 人工重放"都只能成功一次。
     * 状态以参数传入而不是写死在 JPQL 里：枚举字面量在 JPQL 中依赖
     * Hibernate 的解析能力，传参更稳，也不会因为枚举改名而编译不过却运行时报错。
     */
    @Modifying
    @Query("UPDATE AuctionNotification n SET n.deliveryStatus = :sentStatus, "
            + "n.sentAt = :now, n.updateTime = :now, n.lastError = null "
            + "WHERE n.id = :id AND n.deliveryStatus IN :fromStatuses")
    int markSent(@Param("id") Long id,
                 @Param("now") LocalDateTime now,
                 @Param("sentStatus") NotifyDeliveryStatus sentStatus,
                 @Param("fromStatuses") java.util.Collection<NotifyDeliveryStatus> fromStatuses);

    /** 失败/放弃：写入重试次数、下次时间与错误原因。 */
    @Modifying
    @Query("UPDATE AuctionNotification n SET n.deliveryStatus = :status, n.retryCount = :retryCount, "
            + "n.nextRetryAt = :nextRetryAt, n.lastError = :error, n.updateTime = :now WHERE n.id = :id")
    int markDelivery(@Param("id") Long id,
                     @Param("status") NotifyDeliveryStatus status,
                     @Param("retryCount") int retryCount,
                     @Param("nextRetryAt") LocalDateTime nextRetryAt,
                     @Param("error") String error,
                     @Param("now") LocalDateTime now);
}
