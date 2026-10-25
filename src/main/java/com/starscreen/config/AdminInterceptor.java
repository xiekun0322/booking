package com.starscreen.config;

import com.starscreen.common.RoleConstants;
import com.starscreen.common.SessionConstants;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Optional;

@Component
public class AdminInterceptor implements HandlerInterceptor {

    @Autowired
    private UserRepository userRepository;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        HttpSession session = request.getSession(false);
        Object role = session == null ? null : session.getAttribute(SessionConstants.ROLE);
        Object userId = session == null ? null : session.getAttribute(SessionConstants.USER_ID);

        // 1. 如果 Session 里连 admin 标识都没有，直接拦截
        if (!RoleConstants.ADMIN.equals(role) || userId == null) {
            return handleUnauthorized(request, response);
        }

        // 2. 核心防御：不信任 Session，实时查库验证该用户状态
        Optional<User> userOpt = userRepository.findById((Long) userId);

        if (userOpt.isPresent()) {
            User user = userOpt.get();
            // 校验实时状态：角色必须还是 ADMIN，且没有被软删除 (deleted == false)
            if (RoleConstants.ADMIN.equals(user.getRole()) && !user.getDeleted()) {
                return true; // 验证通过，放行
            }
        }

        // 3. 如果查不到，或者角色被降级，或者被注销了，直接拦截
        return handleUnauthorized(request, response);
    }

    // 抽出来的私有方法：专门负责返回 403 拒绝信息
    // 把 Exception 改成具体的 IOException
    private boolean handleUnauthorized(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        if (uri.startsWith("/api/")) {
            response.setStatus(403);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":403,\"message\":\"无管理员权限或账号已失效\",\"data\":null}");
        } else {
            response.sendRedirect("/");
        }
        return false;
    }
}
