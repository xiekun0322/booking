package com.starscreen.service;

import com.starscreen.dto.AdminStats;
import com.starscreen.repository.OrderRepository;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 【功能】后台统计服务。
 *         为 admin.html 提供一次性的统计快照：订单数、票房、TOP 电影、每日趋势。
 *
 * 【调用方】
 *          PageController.admin()
 *            → model.addAttribute("stats", adminService.getStats())
 *            → 渲染 admin.html
 *
 * 【被调用】
 *          OrderRepository 的统计查询方法：
 *            - count()                   总订单数
 *            - countByStatus("pending")  待支付
 *            - countByStatus("paid")     已支付
 *            - countByStatus("cancelled")已取消
 *            - sumPaidTotalPrice()       累计票房
 *            - topMoviesByRevenue()      热销 TOP
 *            - dailyRevenue()            每日趋势
 *
 * 【性能提醒】
 *          getStats() 会发出 7 条 SQL，每次打开后台页都重新计算。
 *          数据量大时建议：
 *          - 加 @Cacheable（Redis / Caffeine）
 *          - 或定时任务预计算写入统计表
 */
@Service
public class AdminService {

    @Autowired
    private OrderRepository orderRepository;

    /**
     * 【功能】构建后台统计视图对象。
     * 【调用链】PageController.admin() → getStats() → 7 次 Repository 查询
     * @return 填充完的 AdminStats，直接传给 Thymeleaf
     */
    public AdminStats getStats() {
        AdminStats stats = new AdminStats();

        // -------- 4 个数字卡片 --------
        stats.setTotalOrders(orderRepository.count());                              // SELECT COUNT(*) FROM t_order
        stats.setPendingOrders(orderRepository.countByStatus("pending"));           // WHERE status='pending'
        stats.setPaidOrders(orderRepository.countByStatus("paid"));                 // WHERE status='paid'
        stats.setCancelledOrders(orderRepository.countByStatus("cancelled"));       // WHERE status='cancelled'

        // -------- 累计票房 --------
        Double totalRevenue = orderRepository.sumPaidTotalPrice();
        stats.setTotalRevenue(totalRevenue != null ? BigDecimal.valueOf(totalRevenue) : BigDecimal.ZERO);
        // SUM WHERE status='paid'

        // -------- TOP 电影 + 每日趋势 --------
        stats.setTopMovies(orderRepository.topMoviesByRevenue());                   // GROUP BY movieTitle
        stats.setDailyRevenue(orderRepository.dailyRevenue());                      // GROUP BY 日期前缀

        return stats;
    }
}
