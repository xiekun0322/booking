package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.dto.SeatVO;
import com.starscreen.service.SeatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 【功能】座位 REST API。
 *         为 seat.html 提供某个场次的完整座位布局。
 *
 * 【路径前缀】/api/seats
 *
 * 【调用方】
 *          seat.html: loadSeats()
 *          → GET /api/seats/schedule/{scheduleId}
 *          → 渲染座位图（available 白 / sold 红 / selected 绿）
 *
 * 【被调用】
 *          SeatService.listBySchedule → SeatRepository → MySQL
 *
 * 【返回格式】Result<List<SeatVO>>
 *          SeatVO 屏蔽了 orderId，避免泄露他人订单信息。
 *
 * 【鉴权】
 *          LoginInterceptor 放行 /api/seats/，游客可查询座位。
 *          合理：选座页需要让未登录用户先看座位布局，
 *          真正下单时才拦截（/api/orders 走鉴权）。
 */
@RestController
@RequestMapping("/api/seats")
public class SeatController {

    @Autowired
    private SeatService seatService;

    /**
     * 【功能】按场次查询座位列表。
     * 【调用链】
     *   seat.html: fetch('/api/seats/schedule/' + scheduleId)
     *   → SeatController.listBySchedule(scheduleId)
     *   → SeatService.listBySchedule
     *   → SeatRepository.findByScheduleIdOrderByRowNumAscColNumAsc
     *   → List<SeatVO>
     * @param scheduleId 场次 ID
     * @return Result<List<SeatVO>>，每项含 id/rowNum/colNum/status/label
     */
    @GetMapping("/schedule/{scheduleId}")
    public Result<List<SeatVO>> listBySchedule(@PathVariable Long scheduleId) {
        return Result.success(seatService.listBySchedule(scheduleId));
    }
}
