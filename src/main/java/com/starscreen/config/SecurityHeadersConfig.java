package com.starscreen.config;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

/**
 * 【功能】安全响应头过滤器。
 *         为每个 HTTP 响应添加一组安全头，防止常见浏览器层攻击。
 *         本项目对应 SECURITY.md 中 OWASP ZAP 扫描出的 3 个告警的修复：
 *           - Content Security Policy (CSP) Header Not Set
 *           - Missing Anti-clickjacking Header
 *           - X-Content-Type-Options Header Missing
 *
 * 【调用时机】
 *          Spring Boot 启动时通过 @Bean 注册 FilterRegistrationBean，
 *          之后每个 HTTP 请求都会先经过本过滤器（order=1，最先执行），
 *          再交给 LoginInterceptor 和 Controller。
 *
 * 【调用方】
 *          由 Servlet 容器（内嵌 Tomcat）在每次请求时自动调用 doFilter()。
 *
 * 【被调用】
 *          chain.doFilter(req, res) —— 放行请求到下一环
 *
 * 【顺序】
 *          请求 → securityHeadersFilter → LoginInterceptor → Controller
 */
@Configuration
public class SecurityHeadersConfig {

    /**
     * 【功能】注册安全头过滤器。
     * 【调用链】Spring 启动 → @Bean securityHeadersFilter() → 注册到 Servlet 容器
     * @return FilterRegistrationBean 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<Filter> securityHeadersFilter() {
        FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {
                HttpServletResponse response = (HttpServletResponse) res;

                // 1. 防点击劫持：禁止页面被 iframe 嵌套
                //    → 修复 ZAP：Missing Anti-clickjacking Header
                response.setHeader("X-Frame-Options", "DENY");

                // 2. 防 MIME 类型嗅探：浏览器不猜测 Content-Type
                //    → 修复 ZAP：X-Content-Type-Options Header Missing
                response.setHeader("X-Content-Type-Options", "nosniff");

                // 3. 内容安全策略：限制脚本、样式、图片、字体的来源
                //    → 修复 ZAP：Content Security Policy (CSP) Header Not Set
                //    注：允许 'unsafe-inline' 是因为项目大量使用内联 <script>，
                //        生产环境应改用 nonce 或外链脚本。
                response.setHeader("Content-Security-Policy",
                        "default-src 'self'; " +
                        "img-src 'self' data:; " +
                        "style-src 'self' 'unsafe-inline'; " +
                        "script-src 'self' 'unsafe-inline'; " +
                        "font-src 'self' data:; " +
                        "connect-src 'self'; " +
                        "frame-ancestors 'none'");

                // 4. 老浏览器的 XSS 保护（现代浏览器已内置 CSP，此处兼容 IE/旧 Chrome）
                response.setHeader("X-XSS-Protection", "1; mode=block");

                // 5. Referrer 策略：跨域时只发送 origin，不泄露完整 URL
                response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

                chain.doFilter(req, res);   // 放行
            }
        });
        registration.addUrlPatterns("/*");   // 拦截所有请求
        registration.setOrder(1);            // 最先执行
        return registration;
    }
}
