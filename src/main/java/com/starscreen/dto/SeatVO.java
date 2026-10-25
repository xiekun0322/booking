package com.starscreen.dto;

import lombok.Data;

/**
 * 【功能】座位展示对象（VO = View Object）。
 *         后端返回给选座页的座位数据结构，
 *         比 Seat 实体多了 label（"3排5座"）字段，方便前端直接用。
 *
 * 【调用方】
 *          - SeatService.listBySchedule(scheduleId)
 *          - SeatController.listBySchedule(scheduleId)
 *
 * 【被调用】
 *          seat.html 前端 fetch('/api/seats/schedule/' + scheduleId) 后渲染：
 *          - rowNum/colNum 决定座位位置
 *          - status 决定颜色（available 白 / sold 红 / locked 灰）
 *          - label 用于点击后显示"3排5座"
 *
 * 【为什么不用 Seat 实体直接返回】
 *          1. 不暴露 orderId（防止前端窥探别人订单）
 *          2. 加 label 字段，省去前端拼接
 *          3. 未来若 Seat 实体加内部字段，VO 可保持稳定
 *
 * 【JSON 示例】
 *          {
 *            "id": 123,
 *            "rowNum": 3,
 *            "colNum": 5,
 *            "status": "available",
 *            "label": "3排5座"
 *          }
 */
@Data
public class SeatVO {

    /** 座位 ID（Seat.id） */
    private Long id;

    /** 第几排 */
    private Integer rowNum;

    /** 第几座 */
    private Integer colNum;

    /** 状态：available / locked / sold */
    private String status;

    /** 座位标签，格式 "3排5座"（由 SeatService 拼接） */
    private String label;
}
