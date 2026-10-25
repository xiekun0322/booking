package com.starscreen.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired
    private LoginInterceptor loginInterceptor;

    @Autowired
    private AdminInterceptor adminInterceptor;
    
    @Autowired
    private AntiReplayInterceptor antiReplayInterceptor; // 🚨 注入防重放拦截器

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        
        // 1. 🚨 注册防重放拦截器 (只拦截写操作的订单接口，保护下单和支付)
        // 注意：放在最前面，让恶意重放请求最快被拦截，节省系统资源
        registry.addInterceptor(antiReplayInterceptor)
                .addPathPatterns("/api/orders/**");

        // 2. 登录拦截
        registry.addInterceptor(loginInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register",
                        "/api/user/send-sms",
                        "/api/user/login-sms",
                        "/api/user/reset-password",
                        "/api/user/current",
                        "/api/seats/**",
                        "/api/movies/**",
                        "/api/schedules/**",
                        "/api/cinemas/**"
                );

        // 3. 管理员拦截（在登录拦截之后）
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**", "/admin/**");
    }
}