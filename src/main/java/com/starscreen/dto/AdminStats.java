package com.starscreen.dto;

import lombok.Data;

import java.util.List;

/**
 * 【功能】后台管理首页的统计视图对象。
 *         一次请求把后台概览需要的所有数据打包返回给 admin.html。
 *
 * 【调用方】
 *          - AdminService.getStats()  构建本对象
 *          - PageController.admin()   model.addAttribute("stats", stats) → admin.html
 *
 * 【被调用】
 *          admin.html 通过 Thymeleaf 读取各字段：
 *          - stats.totalOrders / pendingOrders / paidOrders / cancelledOrders → 4 个数字卡片
 *          - stats.totalRevenue                                               → 累计票房大卡
 *          - stats.topMovies                                                  → TOP5 表格 + 饼图
 *          - stats.dailyRevenue                                               → 折线图
 *
 * 【⚠️ 结构问题】
 *          topMovies 和 dailyRevenue 是 List<Object[]>，靠下标取值：
 *            topMovies:      row[0]=电影名, row[1]=票房, row[2]=订单数
 *            dailyRevenue:   row[0]=日期,   row[1]=票房, row[2]=订单数
 *          一旦 JPQL 列顺序变化，前端静默出错。
 *          建议改造为专门的 Projection 接口或 VO：
 *            class MovieRevenueVO { String movieTitle; Double revenue; Long count; }
 *            class DailyRevenueVO { String date; Double revenue; Long count; }
 */
@Data
public class AdminStats {

    /** 订单总数（COUNT(*)） */
    private long totalOrders;

    /** 待支付订单数（status='pending'） */
    private long pendingOrders;

    /** 已支付订单数（status='paid'） */
    private long paidOrders;

    /** 已取消订单数（status='cancelled'） */
    private long cancelledOrders;

    /** 累计票房（SUM(totalPrice) WHERE status='paid'） */
    private Double totalRevenue;

    /** 热销 TOP 电影：每项 = [电影名, 票房, 订单数]，按票房倒序 */
    private List<Object[]> topMovies;

    /** 每日票房趋势：每项 = [日期, 票房, 订单数]，按日期升序 */
    private List<Object[]> dailyRevenue;
}