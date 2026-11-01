package com.starscreen.auction.repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;

/**
 * 拍品仓储（拍卖库）。
 *
 * <p><b>所有状态流转都在这里用条件 UPDATE 完成</b>，不在 Service 里"先查再改"：
 * <ul>
 *   <li>开拍扫描、关拍扫描、用户取消会并发抢同一行；</li>
 *   <li>"先读后写"会把别人已经推进的状态覆盖回去（例如关拍已 SETTLED，取消又写 CANCELLED）；</li>
 *   <li>条件 UPDATE 的返回值就是答案：1 = 我改成功，0 = 有人先动了，按幂等跳过。</li>
 * </ul>
 *
 * <p>所有 {@code @Modifying} 都带 {@code clearAutomatically = true}：
 * 批量 UPDATE 绕过持久化上下文，不清空的话后续 {@code findById} 会拿到<b>旧快照</b>，
 * 表现为"出价成功后立刻查详情还是旧价格"。
 */
public interface AuctionItemRepository extends JpaRepository<AuctionItem, Long> {

    // ==================== 查询 ====================

    /** 开拍扫描：已到开始时间但仍排期中的拍品。 */
    List<AuctionItem> findTop100ByStatusAndStartTimeBeforeOrderByStartTimeAsc(
            AuctionStatus status, LocalDateTime time);

    /** 关拍扫描：已过结束时间但仍竞拍中的拍品。 */
    List<AuctionItem> findTop100ByStatusAndEndTimeBeforeOrderByEndTimeAsc(
            AuctionStatus status, LocalDateTime time);

    Page<AuctionItem> findByStatus(AuctionStatus status, Pageable pageable);

    /** 待开拍且已过开始时间的条数（监控用：反映调度是否卡住）。 */
    long countByStatusAndStartTimeBefore(AuctionStatus status, LocalDateTime time);

    // ==================== 状态流转 ====================

    /**
     * 通用状态流转（带期望前置状态）。
     *
     * @return 1 表示流转成功，0 表示当前状态不是 {@code from}（已被别人推进或已取消）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionItem i
               SET i.status = :to, i.updateTime = :now
             WHERE i.id = :id AND i.status = :from
            """)
    int transitStatus(@Param("id") Long id,
                      @Param("from") AuctionStatus from,
                      @Param("to") AuctionStatus to,
                      @Param("now") LocalDateTime now);

    /**
     * 成交落库。
     *
     * @param requireExpired 1 = 必须已过期（到点关拍）；0 = 不校验时间（一口价立即成交）
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionItem i
               SET i.status = :status,
                   i.finalPrice = :finalPrice,
                   i.settleTime = :now,
                   i.updateTime = :now
             WHERE i.id = :id
               AND i.status = com.starscreen.auction.enums.AuctionStatus.ACTIVE
               AND (:requireExpired = 0 OR i.endTime IS NULL OR i.endTime <= :now)
            """)
    int settle(@Param("id") Long id,
               @Param("status") AuctionStatus status,
               @Param("finalPrice") BigDecimal finalPrice,
               @Param("now") LocalDateTime now,
               @Param("requireExpired") int requireExpired);

    // ==================== 出价 ====================

    /**
     * 出价落库（唯一的写入口）。
     *
     * <p>四个条件同时成立才允许改价：
     * <ol>
     *   <li>状态仍是 ACTIVE（关拍扫描已经 SETTLED 时这里必须失败）；</li>
     *   <li>出价高于当前价（两人同时出价时后到者失败，避免同价覆盖）；</li>
     *   <li>尚未到结束时间（时钟与扫描有毫秒级窗口，这里再挡一次）；</li>
     *   <li>拍品存在。</li>
     * </ol>
     *
     * <p><b>不更新 version</b>：JPA 规范不允许在批量 UPDATE 里改 {@code @Version} 列，
     * 且本语句自身已经承担了并发控制职责；管理端编辑拍品走实体保存，乐观锁仍然有效。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionItem i
               SET i.currentPrice = :amount,
                   i.leaderId = :userId,
                   i.leaderName = :userName,
                   i.updateTime = :now
             WHERE i.id = :id
               AND i.status = com.starscreen.auction.enums.AuctionStatus.ACTIVE
               AND i.currentPrice < :amount
               AND (i.endTime IS NULL OR i.endTime > :now)
            """)
    int placeBid(@Param("id") Long id,
                 @Param("userId") Long userId,
                 @Param("userName") String userName,
                 @Param("amount") BigDecimal amount,
                 @Param("now") LocalDateTime now);

    /**
     * 顺延结束时间（CAS）。
     *
     * <p>{@code endTime = :expectedEnd} 是必需的：两个线程同时顺延时，
     * 只有第一个能改成功，第二个影响 0 行——否则一次出价会把结束时间推两遍。
     * {@code extendTimes < :maxExtendTimes} 是防"永拍不完"的硬闸门。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionItem i
               SET i.endTime = :newEnd,
                   i.extendTimes = i.extendTimes + 1,
                   i.updateTime = :now
             WHERE i.id = :id
               AND i.status = com.starscreen.auction.enums.AuctionStatus.ACTIVE
               AND i.endTime = :expectedEnd
               AND i.extendTimes < :maxExtendTimes
            """)
    int extendEndTime(@Param("id") Long id,
                      @Param("expectedEnd") LocalDateTime expectedEnd,
                      @Param("newEnd") LocalDateTime newEnd,
                      @Param("now") LocalDateTime now,
                      @Param("maxExtendTimes") int maxExtendTimes);

    /**
     * 回填领先者展示名（跨库解析昵称后异步调用时使用）。
     *
     * <p>条件里带 {@code leaderId}：如果这人已经不是领先者，就不要把名字盖到别人头上。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionItem i
               SET i.leaderName = :name
             WHERE i.id = :id
               AND i.leaderId = :leaderId
               AND (i.leaderName IS NULL OR i.leaderName <> :name)
            """)
    int updateLeaderName(@Param("id") Long id,
                         @Param("leaderId") Long leaderId,
                         @Param("name") String name);
}
