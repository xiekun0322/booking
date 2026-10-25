package com.starscreen.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 【功能】管理员权限拦截器。
 *         校验 Session 里 role == "ADMIN"。
 *         只拦 /api/admin/** 和 /admin/** 路径。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute("role");

        if ("ADMIN".equals(role)) {
            return true;
        }

        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"无管理员权限\",\"data\":null}");
        } else {
            response.sendRedirect("/");
        }
        return false;
    }
}
