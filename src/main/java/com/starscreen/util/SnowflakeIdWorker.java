package com.starscreen.util;

import com.starscreen.common.ClockBackwardsException;

/**
 * 雪花算法 ID 生成器（精简版）
 */
public class SnowflakeIdWorker {

    // 起始时间戳 (2023-01-01)
    private static final long TWITTER_EPOCH = 1672502400000L;
    
    // 机器ID所占的位数
    private static final long WORKER_ID_BITS = 5L;
    
    // 序列号所占的位数
    private static final long SEQUENCE_BITS = 12L;
    
    // 机器ID最大值 (31)
    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    
    // 序列号最大值 (4095)
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);
    
    private final long workerId;
    private long sequence = 0L;
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
            if (sequence == 0) { // 同一毫秒序列号用尽，等待下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        
        // 组装 64 位 ID
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
