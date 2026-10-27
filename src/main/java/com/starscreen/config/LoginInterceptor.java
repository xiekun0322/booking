package com.starscreen.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.starscreen.common.SessionConstants;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * 登录拦截器，作用于 /api/**。
 *
 * <p>策略：所有页面路由一律放行（未登录由模板渲染 needLogin），
 * 需要登录的 API 返回 401 JSON，由前端触发登录模态框。
 *
 * <p><b>NOTE（安全）</b>：白名单用 {@code startsWith} 前缀判断，
 * 因此 {@code /api/movies} 下的 POST/PUT/DELETE 也会被放行 ——
 * 意味着 {@code MovieController} 的写接口无需登录即可调用。
 * 建议将写接口迁移到 /api/admin/** 并删除 MovieController 的写方法。
 *
 * <p><b>NOTE</b>：本类内的白名单与 WebConfig 的 excludePathPatterns 重复，
 * 修改时两处需同步。
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

        // 2. 页面路由全部放行
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
        if (session != null && session.getAttribute(SessionConstants.USER_ID) != null) {
            return true;
        }

        // 5. 未登录 → 401 JSON
        response.setStatus(401);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":401,\"message\":\"请先登录\",\"data\":null}");
        return false;
    }
}
