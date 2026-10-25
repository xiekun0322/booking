package com.starscreen.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class AntiReplayInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate; // 引入 Redis 模板

    private static final long EXPIRE_TIME = 5 * 60 * 1000L; // 5分钟有效期
    
    private static final Logger log = LoggerFactory.getLogger(AntiReplayInterceptor.class);

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 只拦截写操作 (POST, PUT, DELETE)，GET 请求直接放行
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return true;
        }

        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");

        // 2. 校验参数是否存在（严禁缺失参数直接放行！）
        if (timestamp == null || nonce == null) {
            log.warn("拦截请求：缺少防重放 Header (X-Timestamp 或 X-Nonce)");
            writeErrorResponse(response, "非法请求：缺少防重放参数");
            return false; // 这里返回 false，SonarQube 就不叫了
        }

        // 3. 校验时间戳是否超时（防止旧请求重放）
        long currentTime = System.currentTimeMillis();
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            writeErrorResponse(response, "非法请求：时间戳格式错误");
            return false;
        }
        
        if (Math.abs(currentTime - requestTime) > EXPIRE_TIME) {
            log.warn("拦截请求：时间戳已过期，可能遭受重放攻击");
            writeErrorResponse(response, "请求已过期，请重新操作");
            return false;
        }
        // 全部校验通过，放行
        return true;
    }

    /**
     * 辅助方法：向前端输出 JSON 错误信息
     */
    private void writeErrorResponse(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":400,\"message\":\"" + message + "\",\"data\":null}");
        } catch (IOException e) {
            // 如果网络都断了，就不要往外抛了，打个日志警告一下就行了
            log.warn("防重放拦截器：向客户端写入错误信息失败", e);
        }
    }
}
