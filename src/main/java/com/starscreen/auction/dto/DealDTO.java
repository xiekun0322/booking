package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.DealStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 成交单视图（"我的成交"页面数据源）。
 *
 * <p><b>{@code overdue} 是后端算的展示字段，不代表库里状态</b>（决策三 D）：
 * 它只用于让用户在扫描间隔内就看到"已超时"，真正置 CANCELLED 的只有 sweeper。
 * 前端不要拿 {@code overdue} 去推导状态，也不要据此禁止支付——
 * 支付入口有服务端校验，前端拦一道只是为了体验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DealDTO {

    private Long id;

    private String dealNo;

    private Long itemId;

    private String itemTitle;

    private BigDecimal dealPrice;

    private BigDecimal depositAmount;

    private DealStatus status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payDeadline;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime payTime;

    /** 距支付截止剩余秒数（服务端计算，规避客户端时钟漂移）。 */
    private long remainingSeconds;

    /** 展示用：已过截止但尚未被 sweeper 落库。 */
    private boolean overdue;

    private String cancelReason;

    /** 下一步动作：PAY / OVERDUE / DONE。 */
    private String actionHint;
}
