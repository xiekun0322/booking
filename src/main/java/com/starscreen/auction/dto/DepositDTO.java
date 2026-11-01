package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.entity.AuctionDeposit;
import com.starscreen.auction.enums.DepositStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保证金视图（含状态文案所需的原始字段）。
 *
 * <p>状态直接给枚举字符串，前端只做映射不做推导（决策 D1 后端为准）。
 * {@code actionHint} 由后端给出"下一步能做什么"，避免前端把
 * "PAY_FAILED 可以重付"这类规则各写一遍。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DepositDTO {

    private Long id;

    private Long itemId;

    /** 拍品标题（批量查询回填，不做 N+1）。 */
    private String itemTitle;

    private BigDecimal amount;

    private DepositStatus status;

    /** 状态对应的可执行动作：PAY / BID / WAIT_REFUND / NONE。 */
    private String actionHint;

    private String payNo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime refundTriggerAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime refundTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime forfeitTime;

    /** 失败原因（支付失败 / 退款失败时给用户看）。 */
    private String lastError;

    public static DepositDTO of(AuctionDeposit deposit, String itemTitle) {
        return DepositDTO.builder()
                .id(deposit.getId())
                .itemId(deposit.getItemId())
                .itemTitle(itemTitle)
                .amount(deposit.getAmount())
                .status(deposit.getStatus())
                .actionHint(hintOf(deposit.getStatus()))
                .payNo(deposit.getPayNo())
                .payTime(deposit.getPayTime())
                .refundTriggerAt(deposit.getRefundTriggerAt())
                .refundTime(deposit.getRefundTime())
                .forfeitTime(deposit.getForfeitTime())
                .lastError(deposit.getLastError())
                .build();
    }

    /** 由后端给出"下一步动作"，前端不做状态推导。 */
    private static String hintOf(DepositStatus status) {
        return switch (status) {
            case PENDING_PAY, PAY_FAILED, CLOSED -> "PAY";
            case HELD -> "BID";
            case REFUNDING -> "WAIT_REFUND";
            case REFUNDED, REFUND_DEAD, FORFEITED -> "NONE";
        };
    }
}
