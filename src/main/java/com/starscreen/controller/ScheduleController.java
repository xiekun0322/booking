package com.starscreen.controller;

import com.starscreen.entity.Schedule;
import com.starscreen.repository.ScheduleRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 【功能】公开场次 REST API（前台 AJAX）。
 *
 * 【路径前缀】/api/schedules
 *
 * 【⚠️ 与 MovieController 相同的风格问题】
 *          1. 返回裸 List/Schedule，未使用 Result 包装
 *          2. 直接注入 Repository，跳过 Service 层（无业务校验、无事务）
 *          3. @CrossOrigin 与全局 CORS 白名单冲突
 *
 * 【调用方】
 *          前台若需要动态查询场次，可调：
 *          - GET /api/schedules                    全部
 *          - GET /api/schedules/search?movieId=&date=  条件搜索
 *          - GET /api/schedules/{id}               单条
 *          目前 cinemas.html / seat.html 走服务端渲染，未直接调本接口。
 */
@RestController
@RequestMapping("/api/schedules")
public class ScheduleController {

    @Autowired
    private ScheduleRepository scheduleRepository;

    /**
     * 【功能】查询全部场次。
     * 【调用链】GET /api/schedules
     */
    @GetMapping
    public List<Schedule> list() {
        return scheduleRepository.findAll();
    }

    /**
     * 【功能】按电影 ID 和/或日期查询场次。
     * 【调用链】GET /api/schedules/search?movieId=2&date=2026-09-22
     * 【分支逻辑】
     *   movieId + date 都有  → findByMovieIdAndDate
     *   只有 movieId          → findByMovieId
     *   都没有               → findAll（等价于 list()）
     */
    @GetMapping("/search")
    public List<Schedule> search(@RequestParam(required = false) Long movieId,
                                 @RequestParam(required = false) String date) {
        if (movieId != null && date != null) {
            return scheduleRepository.findByMovieIdAndDate(movieId, date);
        }
        if (movieId != null) {
            return scheduleRepository.findByMovieId(movieId);
        }
        return scheduleRepository.findAll();
    }

    /**
     * 【功能】查询单条场次。
     * 【调用链】GET /api/schedules/{id}
     * 【注意】id 不存在返回 null。
     */
    @GetMapping("/{id}")
    public Schedule detail(@PathVariable Long id) {
        return scheduleRepository.findById(id).orElse(null);
    }
}
