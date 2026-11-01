package com.starscreen.auction.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 模拟支付网关回调请求。
 *
 * <p>形状对齐真实网关的 notify：一个支付单号 + 一个结果 + 一个原因。
 * 真实网关还会带签名字段，这里省略但要留着位置——接真网关时补上
 * {@code sign} 与验签逻辑，业务侧无需改动。
 */
@Data
public class MockPayNotifyRequest {

    @NotBlank(message = "支付单号不能为空")
    private String payNo;

    /** SUCCESS / FAIL / CANCEL（大小写不敏感）。 */
    @NotBlank(message = "支付结果不能为空")
    private String result;

    /** 失败或取消原因，可空。 */
    private String reason;
}
