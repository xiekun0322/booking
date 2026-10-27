package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.entity.Schedule;
import com.starscreen.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 【功能】后台场次管理 REST API。
 *         为 admin-schedules.html 提供分页列表 + 增删改查。
 *
 * 【路径前缀】/api/admin/schedules
 *
 * 【调用方】
 *          admin-schedules.html 里所有 fetch：
 *          - GET    /api/admin/schedules?movieId=&page=&size=   列表（可选电影筛选）
 *          - GET    /api/admin/schedules/{id}                   详情
 *          - POST   /api/admin/schedules                        新增
 *          - PUT    /api/admin/schedules/{id}                   更新
 *          - DELETE /api/admin/schedules/{id}                   删除
 *
 * 【被调用】
 *          ScheduleService → ScheduleRepository → MySQL
 *
 * 【⚠️ 已知显示问题】
 *          admin-schedules.html 里显示 ${s.movieTitle}，
 *          但 Schedule 实体没有 movieTitle 字段，会显示"电影 5" 这种占位文本。
 *          修复方式：
 *            a) 加 ScheduleVO（含 movieTitle），Service 层填充
 *            b) 前端用 movies 列表自己映射 movieId → title
 *
 * 【⚠️ 鉴权 / @CrossOrigin】同 AdminMovieController。
 */
@RestController
@RequestMapping("/api/admin/schedules")
public class AdminScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    /**
     * 【功能】分页查询场次。
     * 【调用链】admin-schedules.html: loadSchedules(page)
     *           → GET /api/admin/schedules?page=0&size=10
     *           （筛选项：&movieId=2）
     *           → ScheduleService.listPaged
     * @param movieId 可选，null 表示查全部
     * @return Result<Page<Schedule>>
     */
    @GetMapping
    public Result<Page<Schedule>> list(@RequestParam(required = false) Long movieId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "10") int size) {
        return Result.success(scheduleService.listPaged(movieId, page, size));
    }

    /**
     * 【功能】查询单条场次。
     * 【调用链】admin-schedules.html: editSchedule(id) → 编辑弹窗预填
     */
    @GetMapping("/{id}")
    public Result<Schedule> detail(@PathVariable Long id) {
        return Result.success(scheduleService.getById(id));
    }

    /**
     * 【功能】新增场次。
     * 【调用链】admin-schedules.html: submitBtn
     *           → POST /api/admin/schedules
     *           → ScheduleService.create → validateSchedule
     * 【异常】必填项缺失 → BusinessException
     */
    @PostMapping
    public Result<Schedule> create(@RequestBody Schedule schedule) {
        return Result.success(scheduleService.create(schedule));
    }

    /**
     * 【功能】更新场次。
     */
    @PutMapping("/{id}")
    public Result<Schedule> update(@PathVariable Long id, @RequestBody Schedule schedule) {
        return Result.success(scheduleService.update(id, schedule));
    }

    /**
     * 【功能】删除场次。
     * 【⚠️ 无级联清理 seat】孤儿座位会残留。
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        scheduleService.delete(id);
        return Result.success();
    }
}
