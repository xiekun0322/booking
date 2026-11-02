package com.starscreen.auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionDeal;
import com.starscreen.auction.enums.DealStatus;

/**
 * 成交单数据访问。
 *
 * <p><b>幂等靠唯一键</b>：{@code uk_deal_item(item_id)} 保证"一个拍品一张成交单"。
 * 重复结算（扫描重入、一口价与到点关拍并发）时，第二次 insert 会撞键，
 * 由 {@code DealService} 捕获后当作"已生成"处理，不需要额外的查-判-写。
 */
public interface AuctionDealRepository extends JpaRepository<AuctionDeal, Long> {

    Optional<AuctionDeal> findByItemId(Long itemId);

    Optional<AuctionDeal> findByDealNo(String dealNo);

    boolean existsByItemId(Long itemId);

    Page<AuctionDeal> findByBuyerIdOrderByIdDesc(Long buyerId, Pageable pageable);

    /** 悔拍扫描：待支付且已过支付截止时间。 */
    List<AuctionDeal> findTop100ByStatusAndPayDeadlineBeforeOrderByPayDeadlineAsc(DealStatus status,
                                                                                LocalDateTime time);

    long countByStatus(DealStatus status);

    /**
     * 监控：某状态下已过截止时间的条数。
     *
     * <p>用于 {@code auction.deal.overdue}：{@code PENDING_PAYMENT} 且 {@code pay_deadline < now}
     * 持续大于 0，说明悔拍扫描没跑起来——这笔钱会一直挂在"待支付"，
     * 中标者保证金也永远不会被罚没或退还。
     */
    long countByStatusAndPayDeadlineBefore(DealStatus status, LocalDateTime time);

    /**
     * 支付成功：PENDING_PAYMENT → PAID。
     *
     * <p>带 {@code pay_deadline > :now} 吗？<b>不带</b>——用户可能在截止前一刻付款、
     * 回调稍晚到达；若此时 sweeper 还没把它判为悔拍，这笔钱必须认。
     * 相反，如果 sweeper 已经先把它置为 CANCELLED，这里的条件不成立，
     * 返回 0，由 {@code DealService} 走"超时后付款"的退款分支。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deal
               SET status = 'PAID',
                   pay_time = :now,
                   update_time = :now,
                   version = version + 1
             WHERE id = :id
               AND status = 'PENDING_PAYMENT'
            """, nativeQuery = true)
    int markPaid(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 取消（悔拍 / 人工）：PENDING_PAYMENT → CANCELLED。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deal
               SET status = 'CANCELLED',
                   cancel_reason = :reason,
                   cancel_time = :now,
                   update_time = :now,
                   version = version + 1
             WHERE id = :id
               AND status = 'PENDING_PAYMENT'
            """, nativeQuery = true)
    int cancel(@Param("id") Long id, @Param("reason") String reason, @Param("now") LocalDateTime now);

    /**
     * 悔拍判定（sweeper 专用）：多带一个 {@code pay_deadline < :now}。
     *
     * <p>为什么不能复用 {@link #cancel}：sweeper 读到"已超时"之后，用户可能正好付款成功，
     * 此时 deal 已变 PAID。若 {@code cancel} 只校验状态，就会把已付款的单子取消掉。
     * 加上时间条件后，已付款的记录状态不是 PENDING_PAYMENT，条件自然不成立（双保险）。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_deal
               SET status = 'CANCELLED',
                   cancel_reason = 'PAY_TIMEOUT',
                   cancel_time = :now,
                   update_time = :now,
                   version = version + 1
             WHERE id = :id
               AND status = 'PENDING_PAYMENT'
               AND pay_deadline < :now
            """, nativeQuery = true)
    int cancelOverdue(@Param("id") Long id, @Param("now") LocalDateTime now);
}
