package com.starscreen.auction.support;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;

import org.springframework.stereotype.Component;

/**
 * 拍卖业务编号生成器（雪花算法简化版：时间戳 + 实例号 + 序列）。
 *
 * <p><b>为什么不用数据库自增</b>：业务编号要出现在用户界面、对账单、支付单号里。
 * 自增 ID 会暴露"总共拍了多少单"，也能被枚举——这是商业信息泄露。
 * 雪花号单调递增（便于排序）、无中心依赖（不额外查库）、位数可控。
 *
 * <p><b>单实例内 {@code synchronized}</b>：本机每秒最多 4096 个号，
 * 拍卖场景远达不到这个量级；多实例靠 workerId 区分，不依赖分布式 ID 服务。
 */
@Component
public class AuctionIdGenerator {

    /** 起始纪元：2024-01-01，缩短位数并延长可用年限。 */
    private static final long EPOCH = 1704067200000L;

    /** 每毫秒序列位数。 */
    private static final long SEQUENCE_BITS = 12L;
    private static final long MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;

    /** 实例号位数（1024 个实例足够）。 */
    private static final long WORKER_BITS = 10L;

    private final long workerId = resolveWorkerId();

    private long lastMillis = -1L;
    private long sequence = 0L;

    /** 纯数字 ID。 */
    public synchronized long nextId() {
        long now = Instant.now().toEpochMilli();
        if (now < lastMillis) {
            // 时钟回拨：不阻塞等待（会拖垮出价接口），直接沿用上一毫秒并递增序列。
            // 代价是回拨期间 ID 不再严格单调，但唯一性仍然成立。
            now = lastMillis;
        }
        if (now == lastMillis) {
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 同一毫秒内序列用尽：借用下一毫秒（最多等 1ms）
                now = lastMillis + 1;
            }
        } else {
            sequence = 0L;
        }
        lastMillis = now;
        // 位布局：[时间戳 41][实例号 10][序列 12]
        return ((now - EPOCH) << (SEQUENCE_BITS + WORKER_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    /** 带前缀的业务号，如 {@code AD2024...} / {@code MP...} / {@code AI...}。 */
    public String nextBizNo(String prefix) {
        return prefix + nextId();
    }

    /** 拍品业务编号。 */
    public String nextItemNo() {
        return nextBizNo("AI");
    }

    /** 成交单号。 */
    public String nextDealNo() {
        return nextBizNo("AD");
    }

    /** 支付单号。 */
    public String nextPayNo() {
        return nextBizNo("MP");
    }

    private static long resolveWorkerId() {
        try {
            // 取主机名哈希低 10 位：多实例部署时天然区分，不需要额外配置
            return Math.abs(InetAddress.getLocalHost().getHostName().hashCode()) & 0x3FF;
        } catch (UnknownHostException e) {
            return 1L;
        }
    }
}
