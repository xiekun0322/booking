package com.starscreen.repository;

import com.starscreen.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 【功能】订单数据访问层。
 *         除了继承的基础 CRUD，还有统计类查询（票房、TOP5、每日趋势）。
 *
 * 【调用方】
 *          - OrderService             下单/支付/取消/超时/分页查询
 *          - OrderController          API
 *          - PageController           订单列表/详情/后台首页
 *          - AdminService.getStats()  后台统计
 *
 * 【被调用】
 *          Spring Data JPA + Hibernate → MySQL t_order 表
 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    // ==================== 基础查询 ====================

    /**
     * 【功能】按状态查询订单。
     * 【SQL】SELECT * FROM t_order WHERE status = ?
     * 【调用链】OrderService.cancelExpiredOrders() → 找出所有 pending 订单
     * @param status pending / paid / cancelled
     */
    List<Order> findByStatus(String status);

    /**
     * 【功能】全部订单，按 ID 倒序。
     * 【调用链】OrderService.listPaged(null, ...) → 管理端订单列表
     */
    List<Order> findAllByOrderByIdDesc();

    /**
     * 【功能】按状态查询，ID 倒序。
     */
    List<Order> findByStatusOrderByIdDesc(String status);

    /**
     * 【功能】分页 + 倒序，全部订单。
     * 【调用链】OrderService.listPaged(status, page, size)（管理端）
     */
    Page<Order> findAllByOrderByIdDesc(Pageable pageable);

    /**
     * 【功能】分页 + 状态过滤 + 倒序。
     */
    Page<Order> findByStatusOrderByIdDesc(String status, Pageable pageable);

    // ==================== 用户隔离查询 ====================

    /**
     * 【功能】查某用户的全部订单。
     * 【SQL】SELECT * FROM t_order WHERE user_id = ? ORDER BY id DESC LIMIT ?, ?
     * 【调用链】
     *   OrderService.listPagedByUser(userId, "all", page, size)
     *   → PageController.orders()      我的订单页
     *   → OrderController.list()       我的订单 API
     */
    Page<Order> findByUserIdOrderByIdDesc(Long userId, Pageable pageable);

    /**
     * 【功能】查某用户某状态的订单。
     * 【调用链】OrderService.listPagedByUser(userId, "paid", ...)
     *          → 我的订单页 "已支付" 标签
     */
    Page<Order> findByUserIdAndStatusOrderByIdDesc(Long userId, String status, Pageable pageable);

    // ==================== 统计查询 ====================

    /**
     * 【功能】统计某状态的订单数。
     * 【SQL】SELECT COUNT(*) FROM t_order WHERE status = ?
     * 【调用链】
     *   AdminService.getStats() → 后台 4 个统计卡片（总数/待支付/已支付/已取消）
     */
    long countByStatus(String status);

    /**
     * 【功能】已支付订单的总票房。
     * 【JPQL】SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o WHERE o.status = 'paid'
     * 【调用链】AdminService.getStats() → stats.totalRevenue → admin.html 大数字卡片
     */
    @Query("SELECT COALESCE(SUM(o.totalPrice), 0) FROM Order o WHERE o.status = 'paid'")
    Double sumPaidTotalPrice();

    /**
     * 【功能】热销 TOP 电影（按票房倒序）。
     * 【JPQL】SELECT movieTitle, SUM(totalPrice), COUNT(*)
     *        FROM Order WHERE status='paid' GROUP BY movieTitle ORDER BY SUM(totalPrice) DESC
     * 【返回】List<Object[]>，每项 = [电影名, 票房, 订单数]
     * 【调用链】
     *   AdminService.getStats() → stats.topMovies
     *   → admin.html 里 TOP5 表格 + 饼图
     * 【⚠️ 注意】返回 Object[] 靠下标取值，前端易碎。建议改造成 Projection 接口或 VO。
     */
    @Query("SELECT o.movieTitle, SUM(o.totalPrice), COUNT(o) " +
           "FROM Order o WHERE o.status = 'paid' " +
           "GROUP BY o.movieTitle " +
           "ORDER BY SUM(o.totalPrice) DESC")
    List<Object[]> topMoviesByRevenue();

    /**
     * 【功能】按天统计票房趋势。
     * 【JPQL】SELECT SUBSTRING(payTime,1,10), SUM(totalPrice), COUNT(*)
     *        FROM Order WHERE status='paid' AND payTime IS NOT NULL
     *        GROUP BY SUBSTRING(payTime,1,10)
     *        ORDER BY SUBSTRING(payTime,1,10) ASC
     * 【返回】List<Object[]>，每项 = ["yyyy-MM-dd", 日票房, 日订单数]
     * 【调用链】
     *   AdminService.getStats() → stats.dailyRevenue
     *   → admin.html 折线图
     * 【⚠️ 注意】用字符串前 10 位当日期，是演示环境的简化写法。
     *            正确做法：payTime 改用 LocalDateTime 类型。
     */
    @Query("SELECT SUBSTRING(o.payTime, 1, 10), SUM(o.totalPrice), COUNT(o) " +
           "FROM Order o WHERE o.status = 'paid' AND o.payTime IS NOT NULL " +
           "GROUP BY SUBSTRING(o.payTime, 1, 10) " +
           "ORDER BY SUBSTRING(o.payTime, 1, 10) ASC")
    List<Object[]> dailyRevenue();
}