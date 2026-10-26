package com.starscreen.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS 全局配置，仅对 /api/** 生效。
 *
 * <p>项目前后端同源部署（localhost:8080），但仍显式声明来源白名单，
 * 防止被其它站点跨域调用 API。
 *
 * <p><b>安全说明</b>：
 * <ul>
 *   <li>{@code allowCredentials(true)} 使 Session ID（Cookie）可跨域携带；</li>
 *   <li>因此不能使用 {@code allowedOrigins("*")}，必须显式列出域名；</li>
 *   <li>若将来某个 Controller 再出现 {@code @CrossOrigin}，
 *       该注解默认放开所有来源，会与本白名单冲突，需删除。</li>
 * </ul>
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:8080", "http://127.0.0.1:8080")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true)
                .maxAge(3600);
    }
}
