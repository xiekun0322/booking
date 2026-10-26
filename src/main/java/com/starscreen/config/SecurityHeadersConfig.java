package com.starscreen.config;

import java.io.IOException;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 安全响应头过滤器。
 *
 * <p>修复 SECURITY.md 中 OWASP ZAP 三项告警：
 * CSP 未设置、缺少 Anti-clickjacking 头、缺少 X-Content-Type-Options。
 *
 * <p>执行顺序（order=1 最先）：
 * {@code securityHeadersFilter → LoginInterceptor → Controller}。
 */
@Configuration
public class SecurityHeadersConfig {

    @Bean
    public FilterRegistrationBean<Filter> securityHeadersFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletResponse response = (HttpServletResponse) res;

                // 1. 防点击劫持
                response.setHeader("X-Frame-Options", "DENY");

                // 2. 禁用 MIME 嗅探
                response.setHeader("X-Content-Type-Options", "nosniff");

                // 3. 内容安全策略
                //    NOTE: 允许 'unsafe-inline' 是因项目大量内联 <script>，
                //    生产环境应改用 nonce 或外链脚本。
                response.setHeader("Content-Security-Policy",
                        "default-src 'self'; "
                        + "img-src 'self' data:; "
                        + "style-src 'self' 'unsafe-inline'; "
                        + "script-src 'self' 'unsafe-inline'; "
                        + "font-src 'self' data:; "
                        + "connect-src 'self'; "
                        + "frame-ancestors 'none'");

                // 4. 旧浏览器 XSS 保护
                response.setHeader("X-XSS-Protection", "1; mode=block");

                // 5. Referrer 策略
                response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

                chain.doFilter(req, res);
            }
        });
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
