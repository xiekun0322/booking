package com.starscreen.config;

import java.io.IOException;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.starscreen.common.RoleConstants;
import com.starscreen.common.SessionConstants;
import com.starscreen.entity.User;
import com.starscreen.repository.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;

/**
 * actuator 端点的鉴权。
 *
 * <p><b>为什么必须是 Filter，而不是拦截器</b>：{@code AdminInterceptor} 注册在
 * {@code RequestMappingHandlerMapping} 上，而 actuator 端点由 Spring Boot 自己的
 * {@code WebMvcEndpointHandlerMapping} 处理——两者是不同的 HandlerMapping，
 * 拦截器根本不会被调用。实测 {@code WebConfig} 里加了 {@code /actuator/**} 之后，
 * 匿名请求仍然返回 200。Filter 在 DispatcherServlet 之前执行，对所有 dispatch 生效。
 *
 * <p><b>放行策略</b>：
 * <ul>
 *   <li>{@code /actuator/health}：<b>匿名放行</b>。它是给负载均衡/K8s 探活用的，
 *       要求带会话会让探活永远失败、实例被反复重启；且已配置
 *       {@code management.endpoint.health.show-details=never}，只暴露 UP/DOWN。</li>
 *   <li>其余 {@code /actuator/**}：必须是管理员，且实时查库确认角色未降级。</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ActuatorGuardFilter extends OncePerRequestFilter {

    private static final String ACTUATOR_PREFIX = "/actuator";

    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!uri.startsWith(ACTUATOR_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }

        // 探活端点不鉴权：否则负载均衡拿不到状态
        if (uri.equals(ACTUATOR_PREFIX + "/health") || uri.startsWith(ACTUATOR_PREFIX + "/health/")) {
            chain.doFilter(request, response);
            return;
        }

        if (isAdmin(request)) {
            chain.doFilter(request, response);
            return;
        }

        response.setStatus(403);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"无管理员权限或账号已失效\",\"data\":null}");
    }

    /**
     * 与 {@code AdminInterceptor} 同一套判断：先看会话，再查库。
     *
     * <p>查库这一步不能省——会话里的 role 是登录时的快照，管理员被降级后
     * 旧会话仍然"看起来是管理员"。
     */
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return false;
        }
        Object role = session.getAttribute(SessionConstants.ROLE);
        Object userId = session.getAttribute(SessionConstants.USER_ID);
        if (!RoleConstants.ADMIN.equals(role) || !(userId instanceof Long id)) {
            return false;
        }
        Optional<User> user = userRepository.findById(id);
        return user.isPresent()
                && RoleConstants.ADMIN.equals(user.get().getRole())
                && !Boolean.TRUE.equals(user.get().getDeleted());
    }
}
