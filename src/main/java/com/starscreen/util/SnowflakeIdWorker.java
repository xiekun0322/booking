package com.starscreen.util;

import com.starscreen.common.ClockBackwardsException;

/**
 * 雪花算法 ID 生成器（精简版）。
 *
 * <p>结构：时间戳（差值）<< 17 | workerId << 12 | sequence。
 * 每毫秒最多 4096 个 ID，机器 ID 范围 0~31。
 *
 * <p>时钟回拨直接抛 {@link ClockBackwardsException}，不做等待重试。
 */
public class SnowflakeIdWorker {

    /** 起始时间戳 2023-01-01 UTC。 */
    private static final long TWITTER_EPOCH = 1672502400000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS  = 12L;

    private static final long MAX_WORKER_ID  = ~(-1L << WORKER_ID_BITS);   // 31
    private static final long SEQUENCE_MASK  = ~(-1L << SEQUENCE_BITS);    // 4095

    private final long workerId;
    private long sequence      = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 不能大于 " + MAX_WORKER_ID + " 或小于 0");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            throw new ClockBackwardsException("时钟倒退，拒绝生成ID");
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {                       // 同毫秒序列号用尽
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;

        return ((timestamp - TWITTER_EPOCH) << (WORKER_ID_BITS + SEQUENCE_BITS))
                | (workerId << SEQUENCE_BITS)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    private long timeGen() {
        return System.currentTimeMillis();
    }
}
