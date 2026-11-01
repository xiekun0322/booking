package com.starscreen.auction.enums;

/**
 * 出价结果码。
 *
 * <p>区分"成功"与"被拒"，以及拒绝是否可恢复——前端据此决定提示语与引导动作：
 * <ul>
 *   <li>{@code SUCCESS} / {@code BUYOUT}：成功（后者表示触发一口价，拍卖立即结束）；</li>
 *   <li>{@code BELOW_MINIMUM}：可立即重试，告知当前最低可出价即可；</li>
 *   <li>{@code DEPOSIT_REQUIRED}：可恢复，引导去报名缴费，<b>不能</b>提示"拍卖已结束"；</li>
 *   <li>{@code NOT_BIDDABLE}：不可恢复（未开始 / 已结束 / 非竞拍中）；</li>
 *   <li>{@code SELF_BID_FORBIDDEN}：不可恢复，自买自卖；</li>
 *   <li>{@code RATE_LIMITED}：可重试（退避后）；</li>
 *   <li>{@code DUPLICATE}：重复提交，返回上次结果。</li>
 * </ul>
 */
public enum BidOutcome {

    /** 出价成功，成为当前领先者。 */
    SUCCESS,

    /** 达到或超过一口价，直接成交。 */
    BUYOUT,

    /** 当前不可出价（未开始 / 已结束 / 非 ACTIVE 状态）。 */
    NOT_BIDDABLE,

    /** 低于最小加价幅度（或已被他人抢先）。 */
    BELOW_MINIMUM,

    /** 卖家不能竞拍自己的标的。 */
    SELF_BID_FORBIDDEN,

    /**
     * 未缴纳保证金，不具备参拍资格。
     *
     * <p>与 {@code NOT_BIDDABLE} 分开：这条是"补缴即可继续"，属于可恢复的拒绝，
     * 前端应引导去报名而不是提示"拍卖已结束"。
     */
    DEPOSIT_REQUIRED,

    /** 出价过于频繁。 */
    RATE_LIMITED,

    /** 幂等键重复，返回首次结果。 */
    DUPLICATE;

    /** 是否代表出价被接受。 */
    public boolean isSuccess() {
        return this == SUCCESS || this == BUYOUT;
    }

    /** 是否值得让客户端立刻重试（其余情况重试也不会变）。 */
    public boolean isRetryable() {
        return this == BELOW_MINIMUM || this == RATE_LIMITED;
    }
}
