package com.starscreen.auction.dto;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.starscreen.auction.enums.NotificationType;

import lombok.Data;

/**
 * 通知展示对象。
 *
 * <p>前端不做类型到文案的映射（决策 D1：后端为准）：
 * {@code typeText} 由服务端给出，新增类型时前端不需要跟着改。
 */
@Data
public class NotificationDTO {

    private Long id;

    private NotificationType type;

    /** 类型中文名，前端直接展示。 */
    private String typeText;

    private String title;

    private String content;

    private Boolean readFlag;

    private String bizType;

    private Long bizId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /** 类型文案：集中在这里，避免散落在页面里。 */
    public static String textOf(NotificationType type) {
        if (type == null) {
            return "通知";
        }
        return switch (type) {
            case DEAL_WON -> "成交";
            case DEAL_PAID -> "支付成功";
            case DEPOSIT_REFUNDED -> "保证金已退";
            case DEPOSIT_FORFEITED -> "保证金已罚没";
            case DEPOSIT_REFUND_FAILED -> "退款异常";
        };
    }
}
