package com.starscreen.config;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 防重放拦截器，仅作用于 /api/orders/** 的写操作（POST/PUT/DELETE）。
 *
 * <p>校验项：
 * <ol>
 *   <li>X-Timestamp / X-Nonce 头必须存在；</li>
 *   <li>时间戳与服务器时间差 ≤ 5 分钟。</li>
 * </ol>
 *
 * <p><b>FIXME（严重漏洞）</b>：nonce 取到后<b>未写入 Redis、未去重</b>。
 * 任何重放时间戳仍在 5 分钟内的同一请求都可重复下单。
 * 修复：`setIfAbsent("nonce:" + nonce, "1", 5, MINUTES)`，
 * 返回 false 即判定为重放。`redisTemplate` 已注入，正是为此预留。
 */
@Component
@RequiredArgsConstructor
public class AntiReplayInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AntiReplayInterceptor.class);

    /** 允许的最大时钟偏差（毫秒）。 */
    private static final long MAX_CLOCK_SKEW_MILLIS = 5 * 60 * 1000L;

    /** FIXME: 注入后未使用，见类注释。 */
    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 1. 只拦截写操作
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method)
                && !"PUT".equalsIgnoreCase(method)
                && !"DELETE".equalsIgnoreCase(method)) {
            return true;
        }

        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");

        // 2. 参数缺失直接拒绝
        if (timestamp == null || nonce == null) {
            log.warn("拦截请求：缺少防重放 Header (X-Timestamp 或 X-Nonce)");
            writeErrorResponse(response, "非法请求：缺少防重放参数");
            return false;
        }

        // 3. 校验时间戳
        long requestTime;
        try {
            requestTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            writeErrorResponse(response, "非法请求：时间戳格式错误");
            return false;
        }

        if (Math.abs(System.currentTimeMillis() - requestTime) > MAX_CLOCK_SKEW_MILLIS) {
            log.warn("拦截请求：时间戳已过期，可能遭受重放攻击");
            writeErrorResponse(response, "请求已过期，请重新操作");
            return false;
        }

        // FIXME: 此处应做 nonce 去重（写 Redis + setIfAbsent），当前缺失。

        return true;
    }

    /** 输出 JSON 错误信息。 */
    private void writeErrorResponse(HttpServletResponse response, String message) {
        try {
            response.setStatus(HttpStatus.BAD_REQUEST.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":400,\"message\":\"" + message + "\",\"data\":null}");
        } catch (IOException e) {
            log.warn("防重放拦截器：向客户端写入错误信息失败", e);
        }
    }
}
