package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.entity.AuctionItem;
import com.starscreen.auction.enums.AuctionStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 拍品视图。
 *
 * <p><b>后端为准（决策 D1）</b>：前端不做状态推导，这里直接给
 * {@code status} + {@code statusText} + {@code actionHint} + {@code remainingSeconds}。
 * 前端只做展示映射，避免同一套规则在前后端各写一遍后逐渐分叉。
 *
 * <p><b>{@code remainingSeconds} 的语义随状态变化</b>：
 * <ul>
 *   <li>SCHEDULED：距开拍还剩多少秒（此时 {@code endTime} 还早，用启动时间算才有意义）；</li>
 *   <li>ACTIVE：距结束还剩多少秒；</li>
 *   <li>终态：0。</li>
 * </ul>
 * 早期版本对所有状态都用 endTime 计算，待拍卡片会显示"距结束 9 小时"——
 * 用户以为在拍，点进去却不能出价。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuctionItemDTO {

    private Long id;
    private String itemNo;
    private String title;
    private String description;
    private String category;

    private Long sellerId;
    private String sellerName;

    private BigDecimal startPrice;
    private BigDecimal currentPrice;
    private BigDecimal buyoutPrice;
    private BigDecimal bidIncrement;
    private BigDecimal deposit;

    private AuctionStatus status;
    /** 状态中文文案（后端给出，前端不推导）。 */
    private String statusText;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    private Long leaderId;
    private String leaderName;
    private BigDecimal finalPrice;

    /** 有效出价次数。 */
    private long bidCount;

    /** 距"下一个关键时间点"的剩余秒数（见类注释）。 */
    private long remainingSeconds;

    /** 下一步动作：ENROLL / BID / PAY / WATCH / NONE。 */
    private String actionHint;

    public static AuctionItemDTO of(AuctionItem item, long bidCount, long remainingSeconds) {
        return AuctionItemDTO.builder()
                .id(item.getId())
                .itemNo(item.getItemNo())
                .title(item.getTitle())
                .description(item.getDescription())
                .category(item.getCategory())
                .sellerId(item.getSellerId())
                .sellerName(item.getSellerName())
                .startPrice(item.getStartPrice())
                .currentPrice(item.getCurrentPrice())
                .buyoutPrice(item.getBuyoutPrice())
                .bidIncrement(item.getBidIncrement())
                .deposit(item.getDeposit())
                .status(item.getStatus())
                .statusText(statusText(item.getStatus()))
                .startTime(item.getStartTime())
                .endTime(item.getEndTime())
                .leaderId(item.getLeaderId())
                .leaderName(item.getLeaderName())
                .finalPrice(item.getFinalPrice())
                .bidCount(bidCount)
                .remainingSeconds(remainingSeconds)
                .actionHint(actionHint(item.getStatus()))
                .build();
    }

    /** 状态文案集中一处维护，新增枚举时编译器会在这里报缺分支（switch 穷举）。 */
    public static String statusText(AuctionStatus status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case DRAFT -> "草稿";
            case SCHEDULED -> "即将开始";
            case ACTIVE -> "竞拍中";
            case SETTLED -> "已成交";
            case UNSOLD -> "已流拍";
            case DEFAULTED -> "已违约";
            case CANCELLED -> "已取消";
        };
    }

    private static String actionHint(AuctionStatus status) {
        if (status == null) {
            return "NONE";
        }
        return switch (status) {
            case SCHEDULED -> "ENROLL";
            case ACTIVE -> "BID";
            case SETTLED -> "PAY";
            case DRAFT, UNSOLD, DEFAULTED, CANCELLED -> "NONE";
        };
    }
}
