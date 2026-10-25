package com.starscreen.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 【功能】登录拦截器（新版）。
 *         只拦 /api/**；所有页面一律放行。
 *         需要登录的 API 返回 401 JSON，前端弹登录模态框。
 */
@Component
public class LoginInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        String uri = request.getRequestURI();

        // 1. 静态资源放行
        if (uri.startsWith("/images/")
                || uri.startsWith("/css/")
                || uri.startsWith("/js/")
                || uri.startsWith("/favicon")) {
            return true;
        }

        // 2. ★ 页面路由全部放行（关键：去掉 sendRedirect("/login")）
        if (!uri.startsWith("/api/")) {
            return true;
        }

        // 3. 白名单 API 放行
        if (uri.startsWith("/api/user/login")
                || uri.startsWith("/api/user/register")
                || uri.startsWith("/api/user/send-sms")
                || uri.startsWith("/api/user/login-sms")
                || uri.startsWith("/api/user/reset-password")
                || uri.startsWith("/api/user/current")
                || uri.startsWith("/api/seats/")
                || uri.startsWith("/api/movies")
                || uri.startsWith("/api/schedules")
                || uri.startsWith("/api/cinemas")) {
            return true;
        }

        // 4. 检查登录态
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            return true;
        }

        // 5. 未登录 → 401 JSON（不再 302）
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
        return false;
    }
}
