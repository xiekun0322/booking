package com.starscreen.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 【功能】下单请求体。
 *         前端 seat.html 用户选完座位后，POST /api/orders 发送的 JSON 结构：
 *         {
 *           "scheduleId": 12,
 *           "seats": ["3排5座", "3排6座"]
 *         }
 *
 * 【调用方】
 *          - OrderController.create(@RequestBody @Valid CreateOrderRequest, HttpSession)
 *          - OrderService.createOrder(userId, scheduleId, seatLabels)
 *
 * 【被调用】
 *          - Jackson：把请求 JSON 反序列化为本对象
 *          - Hibernate Validator：@Valid 触发注解校验，失败抛 MethodArgumentNotValidException
 *          - GlobalExceptionHandler.handleValid() 捕获并返回 400
 *
 * 【校验规则】
 *          scheduleId：@NotNull          场次不能为空
 *          seats：     @NotEmpty         至少选 1 个座位
 *                      @Size(max=6)      最多 6 个座位
 *
 * 【座位标签格式】
 *          由 OrderService.parseSeatLabel() 用正则 ^(\d+)排(\d+)座$ 解析，
 *          前端 seat.html 生成的格式与之一致。
 */
@Data
public class CreateOrderRequest {

    /** 场次 ID（必填），对应 Schedule.id */
    @NotNull(message = "场次 ID 不能为空")
    private Long scheduleId;

    /** 座位标签列表，如 ["1排1座", "1排2座"]，1~6 个 */
    @NotEmpty(message = "请至少选择一个座位")
    @Size(max = 6, message = "一次最多选择 6 个座位")
    private List<String> seats;
}