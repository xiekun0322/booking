package com.starscreen.auction.dto;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 保证金资金事件（发件箱载荷）。
 *
 * <p>两类事件共用同一结构，靠 {@code eventType} 区分：
 * <ul>
 *   <li>{@code DEPOSIT_REFUND_TRIGGER}：写退款触发时间，worker 随后原路退回；</li>
 *   <li>{@code DEPOSIT_FORFEIT}：悔拍罚没，HELD → FORFEITED，不退。</li>
 * </ul>
 *
 * <p><b>三个用户字段的含义</b>（互斥使用，都允许为空）：
 * <ul>
 *   <li>{@code excludeUserId}：排除某人——中标成交时给"未中标者"退款，要把中标者排除；</li>
 *   <li>{@code includeUserId}：只退某人——中标者付完成交款，只退他自己；</li>
 *   <li>都为 null：退该拍品下全部 HELD 记录（流拍场景）。</li>
 * </ul>
 * 用参数表达范围而不是"发 N 条单人事件"，是为了让一次关拍只产生<b>一条</b>消息：
 * 参拍人数从 10 涨到 1000 时，消息量不跟着涨。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionDepositEvent {

    /** DEPOSIT_REFUND_TRIGGER / DEPOSIT_FORFEIT。 */
    private String eventType;

    /** 拍品 ID。 */
    private Long itemId;

    /** 排除的用户（中标者）。 */
    private Long excludeUserId;

    /** 只处理该用户（中标者付款后）。 */
    private Long includeUserId;

    /** 成交单 ID，仅罚没事件携带，便于追溯。 */
    private Long dealId;

    /** 触发原因：SOLD_UNPAID / UNSOLD / DEAL_PAID / PAY_TIMEOUT。 */
    private String reason;

    private LocalDateTime occurredAt;
}
