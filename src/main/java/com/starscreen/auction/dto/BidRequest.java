package com.starscreen.auction.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 出价请求。 */
@Data
public class BidRequest {

    @NotNull(message = "出价金额不能为空")
    @DecimalMin(value = "0.01", message = "出价金额必须大于 0")
    private BigDecimal amount;

    /**
     * 幂等键：由客户端生成（建议 UUID），同一次出价的网络重试必须复用同一个值。
     *
     * <p>服务端用它区分"用户连点两次"和"用户真的想加两次价"。
     * 缺这个字段就无法安全重试——超时重发会变成两次真实加价。
     */
    @NotBlank(message = "缺少幂等键")
    @Size(max = 64, message = "幂等键最长 64 字符")
    private String idempotencyKey;
}
