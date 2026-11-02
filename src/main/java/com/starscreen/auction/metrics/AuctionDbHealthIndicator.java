package com.starscreen.auction.metrics;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 拍卖库连通性健康检查。
 *
 * <p><b>为什么需要单独一个</b>：Spring Boot 内置的 {@code db} 指标只覆盖
 * {@code @Primary} 的数据源（业务库）。拍卖库是第二个独立数据源，
 * 它挂掉时 readiness 仍然报 UP，实例会被继续放流量进来——然后所有拍卖接口 500。
 *
 * <p>用 {@code SELECT 1} 而不是 {@code count(*)}：健康检查会被高频调用，
 * 不能在业务大表上做全表统计。
 *
 * <p>结果只进 {@code /actuator/health}（{@code show-details=never} 时只暴露 UP/DOWN），
 * 而该端点匿名可达，所以这里不能放任何敏感明细。
 */
@Component("auctionDb")
public class AuctionDbHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public AuctionDbHealthIndicator(@Qualifier("auctionDataSource") DataSource auctionDataSource) {
        this.jdbcTemplate = new JdbcTemplate(auctionDataSource);
        // 健康检查必须快速失败：默认没有查询超时，库卡住会把 /actuator/health 一起拖死
        this.jdbcTemplate.setQueryTimeout(3);
    }

    @Override
    public Health health() {
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(one) ? Health.up().build() : Health.down().build();
        } catch (RuntimeException e) {
            return Health.down().withDetail("reason", e.getClass().getSimpleName()).build();
        }
    }
}
