package com.starscreen.util;

/**
 * 雪花算法 ID 生成器（精简版）
 */
public class SnowflakeIdWorker {

    // 起始时间戳 (2023-01-01)
    private final long twepoch = 1672502400000L;
    // 机器ID所占的位数
    private final long workerIdBits = 5L;
    // 序列号所占的位数
    private final long sequenceBits = 12L;
    // 机器ID最大值 (31)
    private final long maxWorkerId = ~(-1L << workerIdBits);
    // 序列号最大值 (4095)
    private final long sequenceMask = ~(-1L << sequenceBits);

    private final long workerId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdWorker(long workerId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException("workerId 不能大于 " + maxWorkerId + " 或小于 0");
        }
        this.workerId = workerId;
    }

    public synchronized long nextId() {
        long timestamp = timeGen();
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("时钟倒退，拒绝生成ID");
        }
        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) { // 同一毫秒序列号用尽，等待下一毫秒
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }
        lastTimestamp = timestamp;
        
        // 组装 64 位 ID
        return ((timestamp - twepoch) << (workerIdBits + sequenceBits))
                | (workerId << sequenceBits)
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

/**
 * 完整版雪花算法 (Snowflake)
 */
//public class SnowflakeIdWorker {
//
//    // 起始时间戳 (2023-01-01 00:00:00)
//    private final long twepoch = 1672502400000L;
//
//    // 机器ID所占的位数
//    private final long workerIdBits = 5L;
//    // 数据中心ID所占的位数
//    private final long datacenterIdBits = 5L;
//
//    // 支持的最大机器ID (31)
//    private final long maxWorkerId = ~(-1L << workerIdBits);
//    // 支持的最大数据中心ID (31)
//    private final long maxDatacenterId = ~(-1L << datacenterIdBits);
//
//    // 序列号所占的位数 (12位)
//    private final long sequenceBits = 12L;
//
//    // 机器ID向左移12位
//    private final long workerIdShift = sequenceBits;
//    // 数据中心ID向左移17位(12+5)
//    private final long datacenterIdShift = sequenceBits + workerIdBits;
//    // 时间截向左移22位(12+5+5)
//    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits;
//
//    // 生成序列的掩码，这里为4095 (0b111111111111)
//    private final long sequenceMask = ~(-1L << sequenceBits);
//
//    // 工作机器ID(0~31)
//    private final long workerId;
//    // 数据中心ID(0~31)
//    private final long datacenterId;
//    // 毫秒内序列(0~4095)
//    private long sequence = 0L;
//    // 上次生成ID的时间截
//    private long lastTimestamp = -1L;
//
//    /**
//     * 构造函数
//     * @param workerId 机器ID (0~31)
//     * @param datacenterId 数据中心ID (0~31)
//     */
//    public SnowflakeIdWorker(long workerId, long datacenterId) {
//        if (workerId > maxWorkerId || workerId < 0) {
//            throw new IllegalArgumentException(String.format("worker Id 不能大于 %d 或小于 0", maxWorkerId));
//        }
//        if (datacenterId > maxDatacenterId || datacenterId < 0) {
//            throw new IllegalArgumentException(String.format("datacenter Id 不能大于 %d 或小于 0", maxDatacenterId));
//        }
//        this.workerId = workerId;
//        this.datacenterId = datacenterId;
//    }
//
//    /**
//     * 生成下一个ID
//     */
//    public synchronized long nextId() {
//        long timestamp = timeGen();
//
//        // 【核心防御】：处理时钟回拨问题
//        if (timestamp < lastTimestamp) {
//            long offset = lastTimestamp - timestamp;
//            if (offset <= 5) {
//                // 如果时间回拨在 5ms 以内，等待两倍时间再生成
//                try {
//                    wait(offset << 1);
//                    timestamp = timeGen();
//                } catch (Exception e) {
//                    throw new RuntimeException("等待时钟追赶异常", e);
//                }
//            } else {
//                // 如果回拨太多，直接抛异常拒绝生成，防止产生重复ID
//                throw new RuntimeException(String.format("时钟回拨异常，拒绝生成ID。时间差: %d ms", offset));
//            }
//        }
//
//        if (lastTimestamp == timestamp) {
//            // 同一毫秒内，序列号自增
//            sequence = (sequence + 1) & sequenceMask;
//            if (sequence == 0) {
//                // 同一毫秒内序列号用尽(超过4096)，阻塞到下一个毫秒
//                timestamp = tilNextMillis(lastTimestamp);
//            }
//        } else {
//            // 时间戳改变，序列号重置为0
//            sequence = 0L;
//        }
//
//        lastTimestamp = timestamp;
//
//        // 【拼装ID】：位运算，极高效率
//        return ((timestamp - twepoch) << timestampLeftShift) // 时间戳部分
//                | (datacenterId << datacenterIdShift)        // 数据中心部分
//                | (workerId << workerIdShift)                // 机器标识部分
//                | sequence;                                  // 序列号部分
//    }
//
//    /**
//     * 阻塞到下一个毫秒，直到获得新的时间戳
//     */
//    protected long tilNextMillis(long lastTimestamp) {
//        long timestamp = timeGen();
//        while (timestamp <= lastTimestamp) {
//            timestamp = timeGen();
//        }
//        return timestamp;
//    }
//
//    /**
//     * 返回以毫秒为单位的当前时间
//     */
//    protected long timeGen() {
//        return System.currentTimeMillis();
//    }
//}

/**
* 时钟回拨（Clock Backwards）解决方案
*/
//if (timestamp < lastTimestamp) { // 发现时钟倒退了
//    long offset = lastTimestamp - timestamp;
//    if (offset <= 5) { // 如果倒退在 5 毫秒以内
//        wait(offset << 1); // 咱们就等个 6~10 毫秒，等时钟追上来
//        timestamp = timeGen();
//    } else { // 如果倒退太多（比如有人手动改了服务器时间）
//        throw new RuntimeException("时钟回拨异常！"); // 坚决不生成，宁可报错也不产生重复ID
//    }
//}
