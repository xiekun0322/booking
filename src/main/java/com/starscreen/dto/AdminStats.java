package com.starscreen.dto;

import java.math.BigDecimal;
import java.util.List;

import com.starscreen.repository.DailyRevenueProjection;

import lombok.Data;

/**
 * 后台首页统计视图对象，一次性打包所有概览数据。
 *
 * <p><b>NOTE</b>：{@link #topMovies} / {@link #dailyRevenue} 是 {@code List<Object[]>}，
 * 靠下标取值（0=名称/日期，1=票房，2=订单数），列顺序变化前端会静默出错。
 * 建议改造为 Projection 接口或专门 VO。
 */
@Data
public class AdminStats {

    private long totalOrders;
    private long pendingOrders;
    private long paidOrders;
    private long cancelledOrders;

    /** 累计票房（status='paid' 的 totalPrice 之和）。 */
    private BigDecimal totalRevenue;

    /** 每项 = [电影名, 票房, 订单数]，按票房倒序。 */
    private List<Object[]> topMovies;

    /** 每项 = [日期, 票房, 订单数]，按日期升序。 */
    private List<DailyRevenueProjection> dailyRevenue;
}
