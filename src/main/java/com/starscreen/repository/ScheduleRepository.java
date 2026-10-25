package com.starscreen.repository;

import com.starscreen.entity.Schedule;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【功能】场次数据访问层。
 *
 * 【调用方】
 *          - ScheduleService          管理端分页 / 增删改
 *          - PageController           选影院页（findByMovieId）、选座页（findById）
 *          - ScheduleController       前台 REST 接口
 *          - OrderService             下单时 findById 拿价格
 *          - DataInitializer          启动时 save / findAll
 *
 * 【被调用】
 *          Spring Data JPA 运行时生成实现，访问 MySQL schedule 表。
 */
public interface ScheduleRepository extends JpaRepository<Schedule, Long> {

    /**
     * 【功能】按电影 ID 查询该电影的所有场次。
     * 【SQL】SELECT * FROM schedule WHERE movie_id = ?
     * 【调用链】
     *   PageController.cinemas(movieId)     → GET /cinemas/{movieId} 选影院页
     *   ScheduleController.search(movieId)  → GET /api/schedules/search?movieId=2
     * @param movieId 电影 ID
     */
    List<Schedule> findByMovieId(Long movieId);

    /**
     * 【功能】按电影 ID + 影院 ID 精确查询。
     * 【SQL】SELECT * FROM schedule WHERE movie_id = ? AND cinema_id = ?
     * 【⚠️ 注意】目前代码中没有实际调用点，属于预留方法。
     *            如确认用不到可删除，减少维护成本。
     */
    List<Schedule> findByMovieIdAndCinemaId(Long movieId, Long cinemaId);

    /**
     * 【功能】按电影 ID + 日期查询。
     * 【SQL】SELECT * FROM schedule WHERE movie_id = ? AND date = ?
     * 【调用链】ScheduleController.search(movieId, date)
     */
    List<Schedule> findByMovieIdAndDate(Long movieId, String date);

    /**
     * 【功能】管理端：按电影 ID 分页查询（倒序）。
     * 【SQL】SELECT * FROM schedule WHERE movie_id = ? ORDER BY id DESC LIMIT ?, ?
     * 【调用链】
     *   ScheduleService.listPaged(movieId, page, size)
     *   → AdminScheduleController.list(movieId, page, size)
     *   → GET /api/admin/schedules?movieId=2&page=0&size=10
     */
    Page<Schedule> findByMovieIdOrderByIdDesc(Long movieId, Pageable pageable);

    /**
     * 【功能】管理端：全部分页查询（倒序）。
     * 【调用链】ScheduleService.listPaged(null, page, size)
     *          → 后台场次管理页的"全部电影"筛选项
     */
    Page<Schedule> findAllByOrderByIdDesc(Pageable pageable);
}