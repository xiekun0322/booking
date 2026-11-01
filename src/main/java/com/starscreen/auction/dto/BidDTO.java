package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.entity.AuctionBid;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 出价记录视图。
 *
 * <p>只给前端展示需要的字段：金额、是否有效、时间。
 * <b>不返回幂等键</b>——它是客户端的重试凭证，泄露出去等于允许别人替这位用户重放请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidDTO {

    private Long id;

    private Long bidderId;

    private String bidderName;

    private BigDecimal amount;

    private boolean accepted;

    /** 被拒原因（BidOutcome 名），仅 accepted=false 时有值。 */
    private String rejectedReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime bidTime;

    public static BidDTO of(AuctionBid bid) {
        return BidDTO.builder()
                .id(bid.getId())
                .bidderId(bid.getBidderId())
                .bidderName(bid.getBidderName())
                .amount(bid.getAmount())
                .accepted(Boolean.TRUE.equals(bid.getAccepted()))
                .rejectedReason(bid.getRejectedReason())
                .bidTime(bid.getBidTime())
                .build();
    }
}
