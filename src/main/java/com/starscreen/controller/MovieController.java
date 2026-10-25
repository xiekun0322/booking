package com.starscreen.controller;

import com.starscreen.entity.Movie;
import com.starscreen.repository.MovieRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 【功能】公开电影 REST API（前台 AJAX 使用）。
 *         提供最基础的查询 + CRUD。
 *
 * 【路径前缀】/api/movies
 *
 * 【⚠️ 与项目其它 Controller 的风格差异】
 *          1. 返回类型是实体 / List，未使用 Result 包装：
 *             - 前端拿到的是 [{...}, {...}] 而非 {code, message, data}
 *             - 一旦后端异常，前端拿不到统一的错误结构
 *          2. 直接注入 MovieRepository，跳过 Service 层：
 *             - 无 @Transactional、无业务校验
 *             - 与 AdminMovieController 走 MovieService 的方式不一致
 *          3. POST/PUT/DELETE 是公开写操作：
 *             - LoginInterceptor 拦截了，但任何登录用户都能调
 *             - 实际管理操作走 AdminMovieController，本类的写接口形同虚设
 *
 * 【调用方】
 *          - 前端若使用 fetch('/api/movies')，前端拿到的是裸数组
 *          - 目前 index.html / movie-detail.html 走 Thymeleaf 服务端渲染，未直接调本接口
 *          - 保留意义：给未来做前后端分离 / 移动端 API 预留
 *
 * 【⚠️ @CrossOrigin】同其它 Controller，与全局白名单冲突。
 */
@RestController
@RequestMapping("/api/movies")
public class MovieController {

    @Autowired
    private MovieRepository movieRepository;

    /**
     * 【功能】查询全部电影。
     * 【调用链】GET /api/movies
     * 【返回】裸 List<Movie>（不包 Result）
     */
    @GetMapping
    public List<Movie> list() {
        return movieRepository.findAll();
    }

    /**
     * 【功能】按状态查询。
     * 【调用链】GET /api/movies/status?status=showing
     */
    @GetMapping("/status")
    public List<Movie> listByStatus(@RequestParam String status) {
        return movieRepository.findByStatus(status);
    }

    /**
     * 【功能】查询单部电影。
     * 【调用链】GET /api/movies/{id}
     * 【注意】id 不存在返回 null，前端若未判空会渲染异常。
     */
    @GetMapping("/{id}")
    public Movie detail(@PathVariable Long id) {
        return movieRepository.findById(id).orElse(null);
    }

    /**
     * 【功能】新增电影（绕过 Service 层）。
     * 【调用链】POST /api/movies
     * 【⚠️ 无业务校验】title 可以为空，会直接写库。
     */
    @PostMapping
    public Movie add(@RequestBody Movie movie) {
        return movieRepository.save(movie);
    }

    /**
     * 【功能】更新电影（整体覆盖）。
     * 【调用链】PUT /api/movies/{id}
     * 【⚠️ 与 AdminMovieController.update 的局部更新语义不同】
     *          本方法把请求体整个覆盖到 id 对应的记录，
     *          未传的字段会被置为 null。
     */
    @PutMapping("/{id}")
    public Movie update(@PathVariable Long id, @RequestBody Movie movie) {
        movie.setId(id);
        return movieRepository.save(movie);
    }

    /**
     * 【功能】删除电影。
     * 【调用链】DELETE /api/movies/{id}
     */
    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        movieRepository.deleteById(id);
    }
}
