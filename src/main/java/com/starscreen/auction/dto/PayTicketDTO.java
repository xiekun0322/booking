package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.entity.AuctionPayOrder;
import com.starscreen.auction.enums.PayBizType;
import com.starscreen.auction.enums.PayStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模拟收银台凭证。
 *
 * <p>真实网关返回的是 {@code prepay_id} + 唤起参数；模拟实现返回"支付单号 + 收银台地址"，
 * 业务侧拿到的都是一个"可以继续支付"的凭证，调用形状一致。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayTicketDTO {

    private String payNo;

    private PayBizType bizType;

    private Long bizId;

    private BigDecimal amount;

    private PayStatus status;

    /** 模拟收银台地址；已支付/已关闭时为 null。 */
    private String checkoutUrl;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    public static PayTicketDTO of(AuctionPayOrder order, String checkoutUrl) {
        return PayTicketDTO.builder()
                .payNo(order.getPayNo())
                .bizType(order.getBizType())
                .bizId(order.getBizId())
                .amount(order.getAmount())
                .status(order.getStatus())
                .checkoutUrl(checkoutUrl)
                .createTime(order.getCreateTime())
                .build();
    }
}
