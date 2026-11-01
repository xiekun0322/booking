package com.starscreen.auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionOutbox;
import com.starscreen.auction.enums.OutboxStatus;

/**
 * 发件箱数据访问。
 *
 * <p><b>并发模型</b>：多实例 relay 同时扫同一批 PENDING 是常态。
 * 抢占用 {@code locked_until} 租约实现——UPDATE 成功（影响行数=1）才拥有这条记录的投递权，
 * 失败的一方本轮跳过。这样不需要分布式锁，也不需要 SENDING 状态。
 *
 * <p>所有状态流转都用条件 UPDATE，重复调用影响行数为 0，天然幂等。
 */
public interface AuctionOutboxRepository extends JpaRepository<AuctionOutbox, Long> {

    /**
     * 捞取待投递记录：PENDING 且已到重试时间、且不在别人的租约期内。
     *
     * <p>按 id 升序：先产生的事件先发，保证同一拍品的事件顺序大致不乱。
     */
    @Query(value = """
            SELECT * FROM auction_outbox
             WHERE status = 'PENDING'
               AND (locked_until IS NULL OR locked_until < :now)
               AND (next_retry_at IS NULL OR next_retry_at <= :now)
             ORDER BY id ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<AuctionOutbox> findDispatchable(@Param("now") LocalDateTime now,
                                         @Param("limit") int limit);

    /**
     * 抢占租约：只有当前无租约或租约已过期的记录才能被抢到。
     *
     * @return 1 = 抢到投递权；0 = 被其它实例抢先，本轮跳过
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_outbox
               SET locked_until = :lockedUntil
             WHERE id = :id
               AND status = 'PENDING'
               AND (locked_until IS NULL OR locked_until < :now)
            """, nativeQuery = true)
    int claim(@Param("id") Long id,
              @Param("lockedUntil") LocalDateTime lockedUntil,
              @Param("now") LocalDateTime now);

    /** 投递成功：置 SENT 并释放租约。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_outbox
               SET status = 'SENT',
                   locked_until = NULL,
                   next_retry_at = NULL,
                   last_error = NULL,
                   update_time = :now
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markSent(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 投递失败但还没到上限：累加次数、写退避时间、释放租约。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_outbox
               SET retry_count = :retryCount,
                   next_retry_at = :nextRetryAt,
                   last_error = :lastError,
                   locked_until = NULL,
                   update_time = :now
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markRetry(@Param("id") Long id,
                  @Param("retryCount") int retryCount,
                  @Param("nextRetryAt") LocalDateTime nextRetryAt,
                  @Param("lastError") String lastError,
                  @Param("now") LocalDateTime now);

    /** 重试耗尽：置 DEAD，保留 payload 供人工重放。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_outbox
               SET status = 'DEAD',
                   last_error = :lastError,
                   locked_until = NULL,
                   update_time = :now
             WHERE id = :id
               AND status = 'PENDING'
            """, nativeQuery = true)
    int markDead(@Param("id") Long id,
                 @Param("lastError") String lastError,
                 @Param("now") LocalDateTime now);

    /** 清理已投递的历史记录（分批，避免大事务）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM auction_outbox
             WHERE status = 'SENT'
               AND update_time < :before
             LIMIT :limit
            """, nativeQuery = true)
    int purgeSentBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 积压告警用：统计 PENDING / DEAD 数量。 */
    long countByStatus(OutboxStatus status);

    Optional<AuctionOutbox> findFirstByEventTypeAndAggregateIdOrderByIdDesc(String eventType, Long aggregateId);
}
