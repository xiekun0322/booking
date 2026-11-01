package com.starscreen.auction.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.enums.PayStatus;

/**
 * 支付单数据访问。
 *
 * <p>状态流转全部条件 UPDATE：回调可能重复投递（模拟接口被点两次、真实网关重试），
 * 用 {@code WHERE status = 'CREATED'} 这类条件把重复回调变成影响 0 行，
 * 再由调用方决定是否要额外处理。
 */
public interface AuctionPayOrderRepository extends JpaRepository<AuctionPayOrder, Long> {

    Optional<AuctionPayOrder> findByPayNo(String payNo);

    List<AuctionPayOrder> findByBizTypeAndBizIdOrderByIdDesc(PayBizType bizType, Long bizId);

    /** 业务单最近一笔支付（重新支付时用它判断是否已有在途/成功的支付）。 */
    Optional<AuctionPayOrder> findFirstByBizTypeAndBizIdOrderByIdDesc(PayBizType bizType, Long bizId);

    /** 支付成功：CREATED / FAILED / CANCELLED → PAID（允许失败后重试同一笔，实际多会新建单）。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_pay_order
               SET status = 'PAID',
                   paid_time = :now,
                   fail_reason = NULL,
                   update_time = :now
             WHERE pay_no = :payNo
               AND status IN ('CREATED', 'FAILED', 'CANCELLED')
            """, nativeQuery = true)
    int markPaid(@Param("payNo") String payNo, @Param("now") LocalDateTime now);

    /** 支付失败。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_pay_order
               SET status = 'FAILED',
                   fail_reason = :reason,
                   update_time = :now
             WHERE pay_no = :payNo
               AND status = 'CREATED'
            """, nativeQuery = true)
    int markFailed(@Param("payNo") String payNo,
                   @Param("reason") String reason,
                   @Param("now") LocalDateTime now);

    /** 用户取消支付。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_pay_order
               SET status = 'CANCELLED',
                   fail_reason = :reason,
                   update_time = :now
             WHERE pay_no = :payNo
               AND status = 'CREATED'
            """, nativeQuery = true)
    int markCancelled(@Param("payNo") String payNo,
                      @Param("reason") String reason,
                      @Param("now") LocalDateTime now);

    /** 退款完成。 */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE auction_pay_order
               SET status = 'REFUNDED',
                   refund_time = :now,
                   update_time = :now
             WHERE pay_no = :payNo
               AND status = 'PAID'
            """, nativeQuery = true)
    int markRefunded(@Param("payNo") String payNo, @Param("now") LocalDateTime now);

    List<AuctionPayOrder> findByStatusAndPaidTimeBefore(PayStatus status, LocalDateTime time);
}
