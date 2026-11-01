package com.starscreen.config;

import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;

import com.zaxxer.hikari.HikariDataSource;

import jakarta.persistence.EntityManagerFactory;

/**
 * 业务库（票务）数据源：{@code starscreen}。
 *
 * <p><b>为什么必须显式声明</b>：项目有第二个数据源（拍卖库），
 * Spring Boot 的 {@code DataSourceAutoConfiguration} 一旦发现容器里已有 DataSource 就会退让。
 * 若不在这里把业务库也显式定义出来，整个票务侧（订单、座位、用户）会因为没有
 * {@code dataSource} Bean 而启动失败——这是引入多数据源时最常见的"改一处崩全局"。
 *
 * <p><b>@Primary 的意义</b>：所有未指定事务管理器的 {@code @Transactional}
 * 都走这里，票务代码不需要做任何改动。拍卖侧必须显式使用
 * {@link com.starscreen.auction.config.AuctionTransactional}。
 *
 * <p><b>两个 EntityManagerFactory 的扫描范围必须互斥</b>：
 * 业务库扫 {@code com.starscreen.entity}，拍卖库扫 {@code com.starscreen.auction.entity}。
 * 一旦有重叠，Hibernate 会在两个库都尝试映射同一张表，启动时报"表不存在"或
 * 更隐蔽的"写到了错误的库"。
 */
@Configuration
@EnableJpaRepositories(
        basePackages = "com.starscreen.repository",
        entityManagerFactoryRef = BusinessDataSourceConfig.ENTITY_MANAGER_FACTORY,
        transactionManagerRef = BusinessDataSourceConfig.TX_MANAGER)
public class BusinessDataSourceConfig {

    public static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";
    public static final String TX_MANAGER = "transactionManager";

    @Bean
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource")
    public DataSourceProperties businessDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dataSource")
    @Primary
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties businessDataSourceProperties) {
        return businessDataSourceProperties.initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
    }

    @Bean(name = ENTITY_MANAGER_FACTORY)
    @Primary
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        return buildEntityManagerFactory(dataSource, "business", "com.starscreen.entity", 50);
    }

    @Bean(name = TX_MANAGER)
    @Primary
    public PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
        return new JpaTransactionManager(entityManagerFactory);
    }

    /**
     * 两个数据源共用的 EMF 构造逻辑。
     *
     * <p>抽成 public static 而不是各自复制一份：两份 EMF 配置一旦漂移
     * （比如一边开了 ddl-auto、一边没开），排查成本极高且现象随机。
     */
    public static LocalContainerEntityManagerFactoryBean buildEntityManagerFactory(
            DataSource dataSource, String unitName, String packagesToScan, int batchSize) {

        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(dataSource);
        emf.setPackagesToScan(packagesToScan);
        emf.setPersistenceUnitName(unitName);

        HibernateJpaVendorAdapter adapter = new HibernateJpaVendorAdapter();
        // 表结构由 SQL 脚本唯一负责，绝不让 Hibernate 反向生成
        adapter.setGenerateDdl(false);
        adapter.setShowSql(false);
        emf.setJpaVendorAdapter(adapter);

        Map<String, Object> props = new HashMap<>();
        props.put("hibernate.ddl-auto", "none");
        props.put("hibernate.jdbc.batch_size", batchSize);
        props.put("hibernate.order_inserts", true);
        props.put("hibernate.order_updates", true);
        props.put("hibernate.jdbc.time_zone", "Asia/Shanghai");
        // 【必须显式声明】Boot 的自动配置会注入这个命名策略，手工构建 EMF 时不会。
        // 少了它，实体的 camelCase 属性名会原样当成列名（cinemaName），
        // 而库里的列是 cinema_name —— 表现为整站查询报 "Unknown column 'o1_0.cinemaName'"。
        // 这是引入多数据源最典型的一处回归：不报启动错误，只在跑 SQL 时炸。
        props.put("hibernate.physical_naming_strategy",
                "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
        emf.setJpaPropertyMap(props);
        return emf;
    }
}
