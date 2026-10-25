package com.starscreen.service;

import com.starscreen.common.BusinessException;
import com.starscreen.entity.Movie;
import com.starscreen.repository.MovieRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 【功能】电影业务服务。
 *         封装电影的分页查询、按状态查询、增删改。
 *         增删改方法带 @Transactional，操作失败自动回滚。
 *
 * 【调用方】
 *          - AdminMovieController  → 后台电影管理（分页 + CRUD）
 *          - MovieController       → 公开 REST API（只读场景）
 *
 * 【被调用】
 *          - MovieRepository       持久化
 *          - BusinessException     参数非法时抛出
 *
 * 【事务边界】
 *          create / update / delete 标 @Transactional：
 *          - 内部抛 BusinessException（RuntimeException）→ 自动回滚
 *          - 正常返回 → 提交
 *          查询方法不加事务，Spring Data 自带的只读事务已足够。
 */
@Service
public class MovieService {

    private static final Logger log = LoggerFactory.getLogger(MovieService.class);

    @Autowired
    private MovieRepository movieRepository;

    // ==================== 查询 ====================

    /**
     * 【功能】查询全部电影。
     * 【调用链】MovieController.list() / MovieService 内部使用
     * 【性能】未分页，仅适合数据量小的演示项目。
     */
    public List<Movie> listAll() {
        return movieRepository.findAll();
    }

    /**
     * 【功能】分页查询，按 ID 倒序。
     * 【调用链】
     *   AdminMovieController.list(page, size)
     *   → GET /api/admin/movies?page=0&size=10
     * 【注意】size 无上限，前端可传超大值拖垮数据库。
     *         建议在 Controller 层加 @Max(100) 或在这里校验。
     */
    public Page<Movie> listPaged(int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return movieRepository.findAllByOrderByIdDesc(pageable);
    }

    /**
     * 【功能】按状态查询电影。
     * 【调用链】首页展示"正在热映"、"即将上映"列表
     * @param status "showing" / "upcoming"
     */
    public List<Movie> listByStatus(String status) {
        return movieRepository.findByStatus(status);
    }

    /**
     * 【功能】按 ID 查询单部电影。
     * 【调用链】PageController.movieDetail() / 选影院页取电影信息
     * @return 找不到时返回 null（由调用方决定如何处理）
     */
    public Movie getById(Long id) {
        return movieRepository.findById(id).orElse(null);
    }

    // ==================== 增删改 ====================

    /**
     * 【功能】新增电影。
     * 【调用链】
     *   AdminMovieController.create(@RequestBody Movie)
     *   → POST /api/admin/movies
     * 【事务】@Transactional：save 失败自动回滚
     * 【业务规则】
     *   - title 不能为空
     *   - status 为空时默认 "showing"
     *   - poster 为空时默认 "/images/poster1.jpg"
     * 【异常】title 空 → BusinessException("电影名不能为空") → HTTP 200 + code=500
     */
    @Transactional
    public Movie create(Movie movie) {
        // 校验：电影名必填
        if (movie.getTitle() == null || movie.getTitle().trim().isEmpty()) {
            throw new BusinessException("电影名不能为空");
        }
        // 默认状态
        if (movie.getStatus() == null || movie.getStatus().isEmpty()) {
            movie.setStatus("showing");
        }
        // 默认海报
        if (movie.getPoster() == null || movie.getPoster().isEmpty()) {
            movie.setPoster("/images/poster1.jpg");
        }
        movie = movieRepository.save(movie);
        log.info("新增电影：id={}, title={}", movie.getId(), movie.getTitle());
        return movie;
    }

    /**
     * 【功能】更新电影（局部更新）。
     * 【调用链】
     *   AdminMovieController.update(id, @RequestBody Movie)
     *   → PUT /api/admin/movies/{id}
     * 【更新策略】字段级：只有请求里非 null 的字段才覆盖原值，
     *             避免前端漏传某字段时把原数据清空。
     * 【异常】id 不存在 → BusinessException("电影不存在")
     */
    @Transactional
    public Movie update(Long id, Movie movie) {
        Movie existing = movieRepository.findById(id)
                .orElseThrow(() -> new BusinessException("电影不存在"));

        // 逐字段合并（null 表示"不修改"）
        if (movie.getTitle() != null) existing.setTitle(movie.getTitle());
        if (movie.getPoster() != null) existing.setPoster(movie.getPoster());
        if (movie.getScore() != null) existing.setScore(movie.getScore());
        if (movie.getTag() != null) existing.setTag(movie.getTag());
        if (movie.getReleaseDate() != null) existing.setReleaseDate(movie.getReleaseDate());
        if (movie.getStatus() != null) existing.setStatus(movie.getStatus());
        if (movie.getWantCount() != null) existing.setWantCount(movie.getWantCount());
        if (movie.getDirector() != null) existing.setDirector(movie.getDirector());
        if (movie.getActors() != null) existing.setActors(movie.getActors());
        if (movie.getDuration() != null) existing.setDuration(movie.getDuration());

        existing = movieRepository.save(existing);
        log.info("更新电影：id={}, title={}", id, existing.getTitle());
        return existing;
    }

    /**
     * 【功能】删除电影。
     * 【调用链】AdminMovieController.delete(id) → DELETE /api/admin/movies/{id}
     * 【异常】id 不存在 → BusinessException("电影不存在")
     * 【⚠️ 级联问题】
     *   本方法只删 movie 表，不会级联删除关联的 schedule 和 seat。
     *   结果：场次里 movie_id 指向不存在的电影，前端显示"未知电影"。
     *   生产环境应改为软删除（加 deleted 标记）或手工级联清理。
     */
    @Transactional
    public void delete(Long id) {
        Movie movie = movieRepository.findById(id)
                .orElseThrow(() -> new BusinessException("电影不存在"));
        movieRepository.delete(movie);
        log.info("删除电影：id={}, title={}", id, movie.getTitle());
    }
}
