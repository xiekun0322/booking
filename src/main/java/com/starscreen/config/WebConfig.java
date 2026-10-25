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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 1. 登录拦截
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

        // 2. ★ 管理员拦截（在登录拦截之后）
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**", "/admin/**");
    }
}
