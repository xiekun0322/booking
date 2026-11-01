package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.BidOutcome;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 出价结果。
 *
 * <p><b>被拒绝也返回 200</b>：出价失败是高频的正常业务结果（价格被抢先、未缴保证金），
 * 用 4xx 会让前端把"业务提示"和"系统异常"混在一个错误分支里。
 * 只有参数非法、未登录、幂等键冲突这类"请求本身有问题"的情况才用 4xx。
 *
 * <p>{@code currentPrice} 与 {@code minimumNextBid} 都返回，前端不必自己算加价幅度——
 * 加价规则一旦调整（例如按价格区间浮动），前端不需要跟着改。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BidResultDTO {

    private BidOutcome outcome;

    /** 是否被接受（等价于 outcome.isSuccess()，冗余给出便于前端直接用）。 */
    private boolean accepted;

    private String message;

    /** 当前价（拒绝时也给出，用户能立刻按新价重试）。 */
    private BigDecimal currentPrice;

    /** 下次最低可出价 = currentPrice + bidIncrement。 */
    private BigDecimal minimumNextBid;

    private Long leaderId;

    private String leaderName;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    /** 本次出价是否触发了顺延（前端可提示"已延时 5 分钟"）。 */
    private boolean extended;

    public static BidResultDTO rejected(BidOutcome outcome, String message,
                                        BigDecimal currentPrice, BigDecimal minimumNextBid) {
        return BidResultDTO.builder()
                .outcome(outcome)
                .accepted(false)
                .message(message)
                .currentPrice(currentPrice)
                .minimumNextBid(minimumNextBid)
                .build();
    }
}
