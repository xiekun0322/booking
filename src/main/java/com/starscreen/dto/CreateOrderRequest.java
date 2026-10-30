package com.starscreen.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 下单请求体。
 *
 * <pre>
 *   { "scheduleId": 12, "seats": ["3排5座","3排6座"], "captchaTicket": "..." }
 * </pre>
 *
 * <p>座位标签格式由 {@code OrderService.parseSeatLabel} 用正则
 * {@code ^(\d{1,2})排(\d{1,2})座$} 解析，与前端 seat.html 生成格式一致。
 *
 * <p><b>NOTE</b>：{@link #captchaTicket} 目前为<b>可选</b>——仓库尚无签发人机校验票据的端点，
 * 网页端拿不到票据，Service 不做必填校验；接入真实人机校验后再改为必填并核销。
 */
@Data
public class CreateOrderRequest {

    @NotNull(message = "场次 ID 不能为空")
    private Long scheduleId;

    @NotEmpty(message = "请至少选择一个座位")
    @Size(max = 6, message = "一次最多选择 6 个座位")
    private List<String> seats;

    private String captchaTicket;
}
