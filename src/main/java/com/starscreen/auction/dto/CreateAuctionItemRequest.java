package com.starscreen.auction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发布拍品请求。
 *
 * <p><b>没有 deposit 字段</b>：保证金由服务端按规则计算并固化（决策四 C）。
 * 保留这个字段会让运营以为可以逐品覆盖定价，实际被忽略——不如直接不暴露，
 * 让"定价权在服务端"这件事在接口上就成立。
 */
@Data
public class CreateAuctionItemRequest {

    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字")
    private String title;

    @Size(max = 2000, message = "描述最长 2000 字")
    private String description;

    @Size(max = 40, message = "分类最长 40 字")
    private String category;

    /** 卖家展示名（可空，缺省显示"用户{id}"）。 */
    @Size(max = 100, message = "卖家名最长 100 字")
    private String sellerName;

    @NotNull(message = "起拍价不能为空")
    @DecimalMin(value = "0.01", message = "起拍价必须大于 0")
    private BigDecimal startPrice;

    /** 一口价：达到即成交；为空表示不设。 */
    @DecimalMin(value = "0.01", message = "一口价必须大于 0")
    private BigDecimal buyoutPrice;

    @NotNull(message = "加价幅度不能为空")
    @DecimalMin(value = "0.01", message = "加价幅度必须大于 0")
    private BigDecimal bidIncrement;

    @NotNull(message = "开始时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    @NotNull(message = "结束时间不能为空")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;

    /** 自动延时秒数：0 表示不延时；>0 表示"剩余不足该秒数时出价，顺延该秒数"。 */
    @Min(value = 0, message = "自动延时秒数不能为负")
    private Integer autoExtendSeconds;
}
