package com.starscreen;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 星幕影票系统入口。
 *
 * <p>启动方式：
 * <pre>
 *   mvn spring-boot:run
 *   或 java -jar starscreen-ticketing-1.0.0.jar
 * </pre>
 *
 * <p>启动后自动扫描 {@code com.starscreen.*} 下的配置、控制器、服务、仓库。
 *
 * <p><b>@EnableScheduling</b>：目前项目内的 @Scheduled 任务已迁移到 MQ 延迟队列
 * （{@code OrderTimeoutConsumer}），此注解可以保留也可以移除，
 * 若未来新增定时任务则需保留。
 *
 * <p>NOTE: 数据库连接见 application.properties，
 * 首次启动由 DataInitializer 自动初始化数据。
 */
@SpringBootApplication
@EnableScheduling
public class MovieTicketingApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieTicketingApplication.class, args);
    }
}
