package com.starscreen.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * 拦截器注册。
 *
 * <p>执行顺序：防重放 → 登录 → 管理员。
 *
 * <p><b>NOTE</b>：下方登录白名单与 LoginInterceptor 内部白名单重复，
 * 修改时两处需同步。建议长期收敛为一份。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final LoginInterceptor loginInterceptor;
    private final AdminInterceptor adminInterceptor;
    private final AntiReplayInterceptor antiReplayInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {

        // 1. 防重放：只作用于订单写接口
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
                        "/api/cinemas/**");

        // 3. 管理员拦截
        //    注意：actuator 不在这里。它由 Spring Boot 自己的 WebMvcEndpointHandlerMapping
        //    处理，MVC 拦截器对它无效（实测加了也照样匿名 200），
        //    所以改由 ActuatorGuardFilter 在 Servlet 层鉴权。
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**", "/admin/**");
    }
}

