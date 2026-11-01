package com.starscreen.auction.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.starscreen.config.BusinessDataSourceConfig;
import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

/**
 * 拍卖库数据源：{@code auction}。
 *
 * <p><b>为什么单独一个库</b>：拍卖是热点写（每秒可能几十次出价），票务是低频写；
 * 混在一个库里，一次出价风暴会把选座下单的连接一起拖慢。分库也隔离了故障域。
 *
 * <p><b>代价</b>：跨库没有本地事务。所以拍卖域内的所有写（拍品、出价、成交单、保证金）
 * 必须留在拍卖库内完成；需要跨域的动作（同步订单、通知）只能走发件箱 + 最终一致。
 * 这也是"成交单建在拍卖库"（决策五 A）的根本原因。
 *
 * <p><b>连接池开得比业务库小</b>：拍卖库的写入是短事务（条件 UPDATE），
 * 不需要大量连接；给太多反而在数据库侧形成锁竞争。
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.starscreen.auction.repository",
        entityManagerFactoryRef = AuctionDataSourceConfig.AUCTION_EMF,
        transactionManagerRef = AuctionDataSourceConfig.AUCTION_TX_MANAGER)
public class AuctionDataSourceConfig {

    public static final String AUCTION_EMF = "auctionEntityManagerFactory";
    public static final String AUCTION_TX_MANAGER = "auctionTransactionManager";

    @Bean
    @ConfigurationProperties(prefix = "app.auction.datasource")
    public DataSourceProperties auctionDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "auctionDataSource")
    @ConfigurationProperties(prefix = "app.auction.datasource.hikari")
    public HikariDataSource auctionDataSource(
            @Qualifier("auctionDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = AUCTION_EMF)
    public LocalContainerEntityManagerFactoryBean auctionEntityManagerFactory(
            @Qualifier("auctionDataSource") DataSource dataSource) {
        return BusinessDataSourceConfig.buildEntityManagerFactory(
                dataSource, "auction", "com.starscreen.auction.entity", 50);
    }

    @Bean(name = AUCTION_TX_MANAGER)
    public PlatformTransactionManager auctionTransactionManager(
            @Qualifier(AUCTION_EMF) EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }
}
