package com.starscreen.auction.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 出价限流（本地滑动窗口）。
 *
 * <p><b>为什么是本地而不是 Redis</b>：本地限流在多实例下只能限制"每个实例各自多少次"，
 * 精度不如集中式。但它的价值在于<b>挡住单用户脚本刷价</b>——那种流量必然打到同一个实例上，
 * 本地窗口足够；而引入 Redis 会把"拍卖能不能出价"绑定到另一个中间件上，
 * Redis 抖动时要么误伤正常用户（fail-closed），要么限流失效（fail-open），
 * 两种都比"少一层精确限流"更糟。
 *
 * <p><b>阈值</b>：同一用户 1 秒内最多 5 次出价。正常人手速到不了；脚本会被直接挡下。
 * 被挡的请求不写库，避免限流本身把出价流水表撑大。
 */
@Component
public class BidRateLimiter {

    private static final int MAX_PER_WINDOW = 5;
    private static final long WINDOW_MILLIS = 1_000L;

    /** 每个用户最近若干次出价的时间戳。用 Deque 便于从头部淘汰过期记录。 */
    private final Map<Long, Deque<Long>> windows = new ConcurrentHashMap<>();

    /**
     * 尝试获取一次出价许可。
     *
     * @return true 表示允许本次出价
     */
    public boolean tryAcquire(Long userId) {
        if (userId == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        Deque<Long> window = windows.computeIfAbsent(userId, key -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > WINDOW_MILLIS) {
                window.pollFirst();
            }
            if (window.size() >= MAX_PER_WINDOW) {
                return false;
            }
            window.addLast(now);
            return true;
        }
    }

    /**
     * 清理空闲用户的窗口。
     *
     * <p>不清会随用户数无限增长——拍卖结束后这些 key 再也不会被访问，
     * 属于典型的内存泄漏。由定时任务每分钟调用一次。
     */
    public void evictIdle(long idleMillis) {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(entry -> {
            Deque<Long> window = entry.getValue();
            synchronized (window) {
                return window.isEmpty() || now - window.peekLast() > idleMillis;
            }
        });
    }

    /** 当前跟踪的用户数（监控/测试用）。 */
    public int trackedUsers() {
        return windows.size();
    }
}
