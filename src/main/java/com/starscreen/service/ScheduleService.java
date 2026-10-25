package com.starscreen.service;

import com.starscreen.common.BusinessException;
import com.starscreen.entity.Schedule;
import com.starscreen.repository.ScheduleRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 【功能】场次业务服务。
 *         管理端：分页查询、增删改场次。
 *
 * 【调用方】
 *          - AdminScheduleController  → 后台场次管理
 *          - AdminService 间接通过 Repository 查询
 *
 * 【被调用】
 *          - ScheduleRepository
 *          - BusinessException     校验失败时抛出
 */
@Service
public class ScheduleService {

    private static final Logger log = LoggerFactory.getLogger(ScheduleService.class);

    @Autowired
    private ScheduleRepository scheduleRepository;

    // ==================== 查询 ====================

    /**
     * 【功能】分页查询场次。
     * 【调用链】
     *   AdminScheduleController.list(movieId, page, size)
     *   → GET /api/admin/schedules?movieId=2&page=0&size=10
     * @param movieId 为 null 时查全部，非 null 时按电影过滤
     */
    public Page<Schedule> listPaged(Long movieId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        if (movieId != null) {
            return scheduleRepository.findByMovieIdOrderByIdDesc(movieId, pageable);
        }
        return scheduleRepository.findAllByOrderByIdDesc(pageable);
    }

    /**
     * 【功能】按 ID 查场次。
     * 【调用链】AdminScheduleController.detail(id) / PageController.seat() 间接调用
     */
    public Schedule getById(Long id) {
        return scheduleRepository.findById(id).orElse(null);
    }

    // ==================== 增删改 ====================

    /**
     * 【功能】新增场次。
     * 【调用链】AdminScheduleController.create(@RequestBody Schedule)
     *          → POST /api/admin/schedules
     * 【事务】@Transactional
     * 【校验】validateSchedule() 检查必填项，price/language 为 null 时填默认值
     */
    @Transactional
    public Schedule create(Schedule schedule) {
        validateSchedule(schedule);
        schedule = scheduleRepository.save(schedule);
        log.info("新增场次：id={}, movieId={}, cinema={}, hall={}, time={}",
                schedule.getId(), schedule.getMovieId(), schedule.getCinemaName(),
                schedule.getHallName(), schedule.getStartTime());
        return schedule;
    }

    /**
     * 【功能】更新场次（字段级局部更新）。
     * 【调用链】AdminScheduleController.update(id, @RequestBody Schedule)
     *          → PUT /api/admin/schedules/{id}
     * 【异常】id 不存在 → "场次不存在"
     */
    @Transactional
    public Schedule update(Long id, Schedule schedule) {
        Schedule existing = scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException("场次不存在"));

        // 逐字段合并：null 表示"不修改"
        if (schedule.getMovieId() != null) existing.setMovieId(schedule.getMovieId());
        if (schedule.getCinemaId() != null) existing.setCinemaId(schedule.getCinemaId());
        if (schedule.getCinemaName() != null) existing.setCinemaName(schedule.getCinemaName());
        if (schedule.getHallName() != null) existing.setHallName(schedule.getHallName());
        if (schedule.getStartTime() != null) existing.setStartTime(schedule.getStartTime());
        if (schedule.getEndTime() != null) existing.setEndTime(schedule.getEndTime());
        if (schedule.getLanguage() != null) existing.setLanguage(schedule.getLanguage());
        if (schedule.getPrice() != null) existing.setPrice(schedule.getPrice());
        if (schedule.getDate() != null) existing.setDate(schedule.getDate());

        existing = scheduleRepository.save(existing);
        log.info("更新场次：id={}", id);
        return existing;
    }

    /**
     * 【功能】删除场次。
     * 【调用链】AdminScheduleController.delete(id)
     * 【异常】id 不存在 → "场次不存在"
     * 【⚠️ 级联问题】不会删除关联的 seat 记录。
     *                结果：孤儿座位残留，新场次若复用 id 会错乱。
     *                生产环境应加 seat 清理逻辑。
     */
    @Transactional
    public void delete(Long id) {
        Schedule schedule = scheduleRepository.findById(id)
                .orElseThrow(() -> new BusinessException("场次不存在"));
        scheduleRepository.delete(schedule);
        log.info("删除场次：id={}", id);
    }

    /**
     * 【功能】校验场次必填项，并填默认值。
     * 【调用链】create() → validateSchedule(schedule)
     * 【必填】movieId / cinemaName / hallName / startTime / date
     * 【默认】price → 35.0；language → "国语 2D"
     * 【异常】字段缺失 → BusinessException
     */
    private void validateSchedule(Schedule s) {
        if (s.getMovieId() == null) throw new BusinessException("电影 ID 不能为空");
        if (s.getCinemaName() == null || s.getCinemaName().trim().isEmpty())
            throw new BusinessException("影院名不能为空");
        if (s.getHallName() == null || s.getHallName().trim().isEmpty())
            throw new BusinessException("影厅名不能为空");
        if (s.getStartTime() == null) throw new BusinessException("开始时间不能为空");
        if (s.getDate() == null) throw new BusinessException("放映日期不能为空");
        if (s.getPrice() == null) s.setPrice(35.0);
        if (s.getLanguage() == null) s.setLanguage("国语 2D");
    }
}