package com.starscreen.config;

import com.starscreen.common.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

@Component
public class AntiReplayInterceptor implements HandlerInterceptor {

    @Autowired
    private StringRedisTemplate redisTemplate; // 🚨 引入 Redis 模板

    private static final long EXPIRE_TIME = 5 * 60 * 1000; // 5分钟有效期

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 只拦截写操作（POST, PUT, DELETE）
        String method = request.getMethod();
        if (!"POST".equalsIgnoreCase(method) && !"PUT".equalsIgnoreCase(method) && !"DELETE".equalsIgnoreCase(method)) {
            return true;
        }

        String timestamp = request.getHeader("X-Timestamp");
        String nonce = request.getHeader("X-Nonce");
        
        // 兼容测试：如果前端还没加 Header，暂时放行（正式上线前删掉这个 if）
        if (timestamp == null && nonce == null) {
            System.out.println("⚠️ 警告: 前端未携带防重放 Header，测试环境已放行！");
            return true; 
        }

        // 1. 校验参数是否存在
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(nonce)) {
            throw new BusinessException("请求非法：缺少防重放参数");
        }

        // 2. 校验时间戳（判断请求是否过期）
        long reqTime;
        try {
            reqTime = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BusinessException("时间戳格式非法");
        }

        long now = System.currentTimeMillis();
        if (Math.abs(now - reqTime) > EXPIRE_TIME) {
            throw new BusinessException("请求已过期，请刷新页面重试");
        }

        // 3. 🚨 Redis 防重：利用 setIfAbsent（等价于 Redis 的 SETNX）
        // 如果设置成功（返回 true），说明这个 nonce 是第一次来；
        // 如果设置失败（返回 false），说明这个 nonce 已经存在，属于重放攻击！
        String redisKey = "replay:nonce:" + nonce;
        Boolean isFirstTime = redisTemplate.opsForValue()
                .setIfAbsent(redisKey, "1", 5, TimeUnit.MINUTES);

        if (Boolean.FALSE.equals(isFirstTime)) {
            throw new BusinessException("请勿重复提交请求");
        }

        return true;
    }
}
