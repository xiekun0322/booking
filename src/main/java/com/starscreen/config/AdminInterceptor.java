package com.starscreen.config;

import java.io.IOException;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.starscreen.common.RoleConstants;
import com.starscreen.common.SessionConstants;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * 管理员权限拦截器，作用于 /api/admin/** 与 /admin/**。
 *
 * <p>策略：
 * <ol>
 *   <li>Session 里 role 必须是 ADMIN，userId 必须存在；</li>
 *   <li>不信任 Session —— 每次都实时查库确认 role 未降级、未软删除。</li>
 * </ol>
 *
 * <p>API 请求返回 403 JSON，页面请求重定向到首页。
 */
@Component
@RequiredArgsConstructor
public class AdminInterceptor implements HandlerInterceptor {

    private final UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute(SessionConstants.ROLE);
        Object userId = session == null ? null : session.getAttribute(SessionConstants.USER_ID);

        // 1. Session 里没有 admin 标识，直接拦截
        if (!RoleConstants.ADMIN.equals(role) || userId == null) {
            return handleUnauthorized(request, response);
        }

        // 2. 实时查库验证用户状态
        Optional<User> userOpt = userRepository.findById((Long) userId);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            // 原写法 !user.getDeleted() 在字段为 null 时 NPE——老数据里 deleted 可能没写值，
            // 会让所有管理端请求 500。按"null 视为未删除"处理，与其它查询口径一致。
            if (RoleConstants.ADMIN.equals(user.getRole()) && !Boolean.TRUE.equals(user.getDeleted())) {
                return true;
            }
        }

        // 3. 查不到 / 角色降级 / 已注销 → 拦截
        return handleUnauthorized(request, response);
    }

    /**
     * API 与 actuator 返回 403 JSON，页面请求重定向首页。
     *
     * <p>actuator 不跟着重定向：监控系统/curl 拿到 302 到首页会当成"服务正常但有跳转"，
     * 真正的原因是没权限——直接 403 才能让人一眼看出是鉴权问题。
     */
    private boolean handleUnauthorized(HttpServletRequest request,
                                       HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/") || uri.startsWith("/actuator")) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":403,\"message\":\"无管理员权限或账号已失效\",\"data\":null}");
        } else {
            response.sendRedirect("/");
        }
        return false;
    }
}

