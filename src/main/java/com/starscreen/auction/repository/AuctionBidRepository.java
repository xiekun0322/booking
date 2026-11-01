package com.starscreen.auction.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionBid;

/**
 * 出价流水仓储（拍卖库）。
 *
 * <p><b>没有"加价/改价"方法</b>：出价只追加，不允许更新金额。
 * 出价流水是审计与纠纷处理的依据，能被改写的流水没有证明力。
 */
public interface AuctionBidRepository extends JpaRepository<AuctionBid, Long> {

    /** 幂等键查询：判断这次提交是不是重试。 */
    Optional<AuctionBid> findByIdempotencyKey(String idempotencyKey);

    /** 拍品出价记录（倒序，最新在前）。 */
    Page<AuctionBid> findByItemIdOrderByIdDesc(Long itemId, Pageable pageable);

    /** 有效出价次数（不含被拒记录）。 */
    long countByItemIdAndAcceptedTrue(Long itemId);

    /**
     * 批量统计多个拍品的有效出价数。
     *
     * <p>列表页若逐个 {@code countByItemId}，一页 20 条就是 20 次查询（典型 N+1）。
     * 这里一次 GROUP BY 取回全部，页面渲染的数据库往返从 21 次降到 2 次。
     *
     * @return 每行为 {@code [itemId, count]}
     */
    @Query("""
            SELECT b.itemId, COUNT(b)
              FROM AuctionBid b
             WHERE b.itemId IN :itemIds AND b.accepted = true
             GROUP BY b.itemId
            """)
    List<Object[]> countAcceptedByItemIds(@Param("itemIds") Collection<Long> itemIds);

    /** 某人在该拍品的最后一次出价（被判"低于最低价"时用于提示）。 */
    Optional<AuctionBid> findFirstByItemIdAndBidderIdOrderByIdDesc(Long itemId, Long bidderId);

    /** 回填出价人展示名（跨库解析昵称后调用；带 bidderId 条件避免覆盖别人的记录）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionBid b
               SET b.bidderName = :name
             WHERE b.id = :id
               AND b.bidderId = :bidderId
               AND (b.bidderName IS NULL OR b.bidderName <> :name)
            """)
    int updateBidderName(@Param("id") Long id,
                         @Param("bidderId") Long bidderId,
                         @Param("name") String name);

    /** 回填存证哈希（异步存证完成后调用）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuctionBid b
               SET b.chainTxHash = :hash
             WHERE b.id = :id AND b.chainTxHash IS NULL
            """)
    int updateChainTxHash(@Param("id") Long id, @Param("hash") String hash);
}
