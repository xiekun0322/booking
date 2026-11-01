package com.starscreen.auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionDeposit;
import com.starscreen.auction.enums.DepositStatus;

/**
 * 保证金数据访问。
 *
 * <p><b>全部状态流转都是条件 UPDATE</b>，没有一处"先查再改"：
 * 保证金是资金相关数据，任何"读-判断-写"在并发下都可能重复扣划或重复退款。
 * 统一返回受影响行数，调用方以 {@code == 1} 作为"本次操作真正生效"的唯一依据。
 */
public interface AuctionDepositRepository extends JpaRepository<AuctionDeposit, Long> {

    Optional<AuctionDeposit> findByUserIdAndItemId(Long userId, Long itemId);

    Optional<AuctionDeposit> findByPayNo(String payNo);

    List<AuctionDeposit> findByUserIdOrderByIdDesc(Long userId);

    List<AuctionDeposit> findByItemIdAndStatus(Long itemId, DepositStatus status);

    boolean existsByUserIdAndItemIdAndStatus(Long userId, Long itemId, DepositStatus status);

    long countByItemIdAndStatus(Long itemId, DepositStatus status);

    /**
     * 写退款触发时间（消费者调用，决策一 C / 决策二 B 的共同落点）。
     *
     * <p>三个条件缺一不可：
     * <ul>
     *   <li>{@code status='HELD'}：只有还在冻结中的才谈得上退；</li>
     *   <li>{@code refund_trigger_at IS NULL}：重复消息不把触发时间往后推（幂等）；</li>
     *   <li>exclude / include 二选一：未中标退全部、中标只退买家。</li>
     * </ul>
     *
     * @param excludeUserId 排除的用户（中标成交场景：中标者此时还不能退）
     * @param includeUserId 只退该用户（中标付款成功场景）；与 exclude 互斥，可都为 null
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET refund_trigger_at = :now,
                   update_time = :now
             WHERE item_id = :itemId
               AND status = 'HELD'
               AND refund_trigger_at IS NULL
               AND (:excludeUserId IS NULL OR user_id <> :excludeUserId)
               AND (:includeUserId IS NULL OR user_id = :includeUserId)
            """, nativeQuery = true)
    int markRefundTrigger(@Param("itemId") Long itemId,
                          @Param("excludeUserId") Long excludeUserId,
                          @Param("includeUserId") Long includeUserId,
                          @Param("now") LocalDateTime now);

    /** 悔拍罚没：HELD → FORFEITED，留痕可审计。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'FORFEITED',
                   forfeit_time = :now,
                   update_time = :now
             WHERE item_id = :itemId
               AND user_id = :userId
               AND status = 'HELD'
            """, nativeQuery = true)
    int forfeit(@Param("itemId") Long itemId,
                @Param("userId") Long userId,
                @Param("now") LocalDateTime now);

    /**
     * 退款 worker 扫表：已到触发时间、未在退避期内的 HELD 记录。
     */
    @Query(value = """
            SELECT * FROM auction_deposit
             WHERE status = 'HELD'
               AND refund_trigger_at IS NOT NULL
               AND refund_trigger_at <= :now
               AND (next_retry_at IS NULL OR next_retry_at <= :now)
             ORDER BY refund_trigger_at ASC
             LIMIT :limit
            """, nativeQuery = true)
    List<AuctionDeposit> findRefundable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 抢占退款权：HELD → REFUNDING。返回 1 才允许发起外部退款调用。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'REFUNDING',
                   update_time = :now
             WHERE id = :id
               AND status = 'HELD'
            """, nativeQuery = true)
    int claimRefund(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 退款成功：REFUNDING → REFUNDED。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'REFUNDED',
                   refund_time = :now,
                   last_error = NULL,
                   update_time = :now
             WHERE id = :id
               AND status = 'REFUNDING'
            """, nativeQuery = true)
    int markRefunded(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 退款失败：REFUNDING 退回 HELD，带退避时间等下一轮。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'HELD',
                   retry_count = :retryCount,
                   next_retry_at = :nextRetryAt,
                   last_error = :lastError,
                   update_time = :now
             WHERE id = :id
               AND status = 'REFUNDING'
            """, nativeQuery = true)
    int markRefundRetry(@Param("id") Long id,
                        @Param("retryCount") int retryCount,
                        @Param("nextRetryAt") LocalDateTime nextRetryAt,
                        @Param("lastError") String lastError,
                        @Param("now") LocalDateTime now);

    /** 退款重试耗尽：REFUNDING → REFUND_DEAD，等人工。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'REFUND_DEAD',
                   last_error = :lastError,
                   update_time = :now
             WHERE id = :id
               AND status = 'REFUNDING'
            """, nativeQuery = true)
    int markRefundDead(@Param("id") Long id,
                       @Param("lastError") String lastError,
                       @Param("now") LocalDateTime now);

    /**
     * 保证金到账：PENDING_PAY / PAY_FAILED / CLOSED → HELD。
     *
     * <p>允许从 PAY_FAILED / CLOSED 进入，是为了支持"用户重新支付 / 重新报名"时
     * 原地复用同一条记录——唯一键 {@code uk_deposit} 不允许新建第二行。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'HELD',
                   pay_no = :payNo,
                   pay_time = :now,
                   next_retry_at = NULL,
                   last_error = NULL,
                   update_time = :now
             WHERE id = :id
               AND status IN ('PENDING_PAY', 'PAY_FAILED', 'CLOSED')
            """, nativeQuery = true)
    int markHeld(@Param("id") Long id, @Param("payNo") String payNo, @Param("now") LocalDateTime now);

    /** 支付失败：仍是待支付语义，允许重新发起。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'PAY_FAILED',
                   last_error = :reason,
                   update_time = :now
             WHERE id = :id
               AND status = 'PENDING_PAY'
            """, nativeQuery = true)
    int markPayFailed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** 用户取消支付：关闭报名，可重新报名时原地复活。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'CLOSED',
                   last_error = :reason,
                   update_time = :now
             WHERE id = :id
               AND status = 'PENDING_PAY'
            """, nativeQuery = true)
    int markClosed(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /** 重新报名：PAY_FAILED / CLOSED → PENDING_PAY。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deposit
               SET status = 'PENDING_PAY',
                   pay_no = NULL,
                   last_error = NULL,
                   update_time = :now
             WHERE id = :id
               AND status IN ('PAY_FAILED', 'CLOSED')
            """, nativeQuery = true)
    int reopen(@Param("id") Long id, @Param("now") LocalDateTime now);
}
