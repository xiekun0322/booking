package com.starscreen.controller;

import com.starscreen.common.Result;
import com.starscreen.entity.Movie;
import com.starscreen.service.MovieService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * 【功能】后台电影管理 REST API。
 *         为 admin-movies.html 提供分页列表 + 增删改查。
 *
 * 【路径前缀】/api/admin/movies
 *
 * 【调用方】
 *          admin-movies.html 里所有 fetch：
 *          - GET    /api/admin/movies?page=0&size=10   列表
 *          - GET    /api/admin/movies/{id}             详情（编辑弹窗预填）
 *          - POST   /api/admin/movies                  新增
 *          - PUT    /api/admin/movies/{id}             更新
 *          - DELETE /api/admin/movies/{id}             删除
 *
 * 【被调用】
 *          MovieService → MovieRepository → MySQL
 *
 * 【鉴权】
 *          LoginInterceptor 只校验"是否登录"，
 *          不校验"是否管理员" → 【任何登录用户都能调这些接口】。
 *          建议加 AdminInterceptor。
 *
 * 【⚠️ @CrossOrigin】
 *          与全局 CorsConfig 白名单冲突（@CrossOrigin 默认放开所有来源）。
 *          生产环境应删除。
 *
 * 【返回格式】统一 Result<...>，前端通过 code !== 200 判断失败
 */
@RestController
@RequestMapping("/api/admin/movies")
@CrossOrigin
public class AdminMovieController {

    @Autowired
    private MovieService movieService;

    /**
     * 【功能】分页查询电影列表。
     * 【调用链】admin-movies.html: loadMovies(page)
     *           → GET /api/admin/movies?page=0&size=10
     *           → MovieService.listPaged
     *           → MovieRepository.findAllByOrderByIdDesc
     * @param page 页码，从 0 开始
     * @param size 每页条数（⚠️ 无上限，建议加 @Max(100)）
     * @return Result<Page<Movie>>
     */
    @GetMapping
    public Result<Page<Movie>> list(@RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "10") int size) {
        return Result.success(movieService.listPaged(page, size));
    }

    /**
     * 【功能】查询单部电影详情。
     * 【调用链】admin-movies.html: editMovie(id)
     *           → GET /api/admin/movies/{id}
     *           → MovieService.getById
     * 【注意】id 不存在时返回 Result.success(null)，
     *         前端会显示"加载失败"，但 HTTP 状态仍是 200。
     */
    @GetMapping("/{id}")
    public Result<Movie> detail(@PathVariable Long id) {
        return Result.success(movieService.getById(id));
    }

    /**
     * 【功能】新增电影。
     * 【调用链】admin-movies.html: submitBtn 点击 → 表单校验通过
     *           → POST /api/admin/movies {title, poster, ...}
     *           → MovieService.create(movie)
     *           → movieRepository.save
     * @param movie 请求体（Jackson 反序列化）
     * @return Result<Movie> 含新建 ID（前端拿到后可刷新列表）
     * 【异常】电影名为空 → BusinessException → GlobalExceptionHandler → Result{code=500}
     */
    @PostMapping
    public Result<Movie> create(@RequestBody Movie movie) {
        return Result.success(movieService.create(movie));
    }

    /**
     * 【功能】更新电影（字段级局部更新）。
     * 【调用链】admin-movies.html: 编辑弹窗提交
     *           → PUT /api/admin/movies/{id}
     *           → MovieService.update
     * 【注意】只有请求里非 null 的字段才会覆盖原值。
     */
    @PutMapping("/{id}")
    public Result<Movie> update(@PathVariable Long id, @RequestBody Movie movie) {
        return Result.success(movieService.update(id, movie));
    }

    /**
     * 【功能】删除电影。
     * 【调用链】admin-movies.html: deleteMovie(id, title)
     *           → DELETE /api/admin/movies/{id}
     *           → MovieService.delete
     * 【⚠️ 无级联】不会删除关联的 schedule/seat，历史订单不受影响（快照设计）。
     * 【返回】Result.success()，无 data
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        movieService.delete(id);
        return Result.success();
    }
}