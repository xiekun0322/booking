package com.starscreen.repository;

import com.starscreen.entity.Movie;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 【功能】电影数据访问层。
 *         继承 JpaRepository，自动获得 save / findById / findAll / deleteById 等基础方法。
 *
 * 【调用方】
 *          - MovieService             业务层调用（listPaged / listByStatus / getById 等）
 *          - AdminMovieController     → MovieService → 本接口
 *          - PageController           直接注入使用（首页、电影详情）
 *          - MovieController          直接注入使用（简单 CRUD）
 *          - DataInitializer          启动时 save / findAll / count
 *
 * 【被调用】
 *          Spring Data JPA 运行时自动生成实现类，SQL 由方法名推导。
 *          底层通过 Hibernate 访问 MySQL 的 movie 表。
 *
 * 【命名规则回顾】
 *          findByStatus           → WHERE status = ?
 *          findByTitleContaining  → WHERE title LIKE %?%
 *          findAllByOrderByIdDesc → ORDER BY id DESC
 */
public interface MovieRepository extends JpaRepository<Movie, Long> {

    /**
     * 【功能】按状态查询电影。
     * 【SQL】SELECT * FROM movie WHERE status = ?
     * 【调用链】
     *   PageController.index()              → 首页展示 "正在热映"/"即将上映"
     *   MovieService.listByStatus()         → 管理端筛选
     *   MovieController.listByStatus()      → /api/movies/status?status=showing
     * @param status "showing" 或 "upcoming"
     */
    List<Movie> findByStatus(String status);

    /**
     * 【功能】按标题模糊搜索。
     * 【SQL】SELECT * FROM movie WHERE title LIKE %?%
     * 【调用链】
     *   PageController.index(keyword)       → 首页搜索框
     * @param keyword 用户输入的关键词（已 trim 处理）
     */
    List<Movie> findByTitleContaining(String keyword);

    /**
     * 【功能】分页查询，按 ID 倒序（最新录入的在前面）。
     * 【SQL】SELECT * FROM movie ORDER BY id DESC LIMIT ?, ?
     * 【调用链】
     *   MovieService.listPaged(page, size)  → AdminMovieController.list()
     *   → GET /api/admin/movies?page=0&size=10
     * 【⚠️ 性能】size 无上限校验，若前端传 size=1000000 会一次拉百万条。
     *            建议在 Controller 上加 @Max(100)。
     * @param pageable 分页参数
     * @return Page<Movie> 含 content / totalPages / totalElements
     */
    Page<Movie> findAllByOrderByIdDesc(Pageable pageable);
}
