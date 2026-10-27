import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.NetworkInterface;

/**
 * 雪花算法 ID 生成器（完整版）
 * - 线程安全
 * - 支持时钟回拨检测与等待
 * - 支持容器/多网卡环境下的机器 ID 自动推导
 */
public class SnowflakeIdGenerator {

    /** 起始纪元：2020-01-01 00:00:00 UTC（可自行修改，一旦上线不可更改） */
    private static final long EPOCH = 1577836800000L;

    /** 各部分占位 */
    private static final long WORKER_ID_BITS      = 5L;
    private static final long DATACENTER_ID_BITS  = 5L;
    private static final long SEQUENCE_BITS       = 12L;

    /** 最大值 */
    private static final long MAX_WORKER_ID      = ~(-1L << WORKER_ID_BITS);      // 31
    private static final long MAX_DATACENTER_ID  = ~(-1L << DATACENTER_ID_BITS);  // 31
    private static final long SEQUENCE_MASK      = ~(-1L << SEQUENCE_BITS);       // 4095

    /** 位移量 */
    private static final long WORKER_ID_SHIFT      = SEQUENCE_BITS;                              // 12
    private static final long DATACENTER_ID_SHIFT  = SEQUENCE_BITS + WORKER_ID_BITS;             // 17
    private static final long TIMESTAMP_SHIFT      = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS; // 22

    /** 允许的最大时钟回拨毫秒数，超过则拒绝生成 */
    private static final long MAX_BACKWARD_MS = 5L;

    private final long workerId;
    private final long datacenterId;
    private long sequence      = 0L;
    private long lastTimestamp = -1L;

    public SnowflakeIdGenerator(long datacenterId, long workerId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                "workerId must be in [0, " + MAX_WORKER_ID + "]");
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                "datacenterId must be in [0, " + MAX_DATACENTER_ID + "]");
        }
        this.datacenterId = datacenterId;
        this.workerId     = workerId;
    }

    /**
     * 生成下一个 ID（线程安全）
     */
    public synchronized long nextId() {
        long timestamp = currentTime();

        // 1. 时钟回拨检测
        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset <= MAX_BACKWARD_MS) {
                // 小回拨：自旋等待时间追上
                try {
                    wait(offset << 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while waiting for clock", e);
                }
                timestamp = currentTime();
                if (timestamp < lastTimestamp) {
                    throw new IllegalStateException(
                        "Clock moved backwards. Refusing to generate id. offset="
                        + (lastTimestamp - timestamp) + "ms");
                }
            } else {
                // 大回拨：拒绝服务，交给上层处理（如切换机器号 / 报警）
                throw new IllegalStateException(
                    "Clock moved backwards too much. Refusing to generate id. offset="
                    + offset + "ms");
            }
        }

        // 2. 同一毫秒内序列号自增
        if (timestamp == lastTimestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 当前毫秒序列号用尽，等待下一毫秒
                timestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新毫秒：序列号随机初始化可防止 ID 顺序泄露（按需开启）
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 3. 拼装 64 位 ID
        return ((timestamp - EPOCH) << TIMESTAMP_SHIFT)
             | (datacenterId      << DATACENTER_ID_SHIFT)
             | (workerId          << WORKER_ID_SHIFT)
             |  sequence;
    }

    /** 阻塞到下一毫秒 */
    private long waitNextMillis(long lastTimestamp) {
        long ts = currentTime();
        while (ts <= lastTimestamp) {
            ts = currentTime();
        }
        return ts;
    }

    private long currentTime() {
        return System.currentTimeMillis();
    }

    /** 解析 ID（调试用） */
    public static String parse(long id) {
        long seq        = id & SEQUENCE_MASK;
        long workerId   = (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
        long dcId       = (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
        long ts         = (id >> TIMESTAMP_SHIFT) + EPOCH;
        return String.format(
            "timestamp=%d(%s), datacenterId=%d, workerId=%d, sequence=%d",
            ts, new java.util.Date(ts), dcId, workerId, seq);
    }

    // ---------------------------------------------------------------
    // 机器 ID 自动推导（容器/云原生环境可用，防止手写冲突）
    // ---------------------------------------------------------------
    public static long autoDatacenterId() {
        try {
            InetAddress ip = InetAddress.getLocalHost();
            NetworkInterface ni = NetworkInterface.getByInetAddress(ip);
            byte[] mac = ni.getHardwareAddress();
            if (mac != null) {
                long id = ((0x000000FF & (long) mac[mac.length - 1])
                        | (0x0000FF00 & (((long) mac[mac.length - 2]) << 8))) >> 6;
                return id % (MAX_DATACENTER_ID + 1);
            }
        } catch (Exception ignore) { /* fallthrough */ }
        return 0L;
    }

    public static long autoWorkerId(long datacenterId) {
        try {
            StringBuilder sb = new StringBuilder();
            for (String s : ManagementFactory.getRuntimeMXBean().getName().split("@")) {
                sb.append(s);
            }
            long pid = Long.parseLong(
                ManagementFactory.getRuntimeMXBean().getName().split("@")[0]);
            return (pid + datacenterId * 1000) % (MAX_WORKER_ID + 1);
        } catch (Exception ignore) {
            return 0L;
        }
    }

    // ---------------------------------------------------------------
    // 单例 + 使用示例
    // ---------------------------------------------------------------
    private static final SnowflakeIdGenerator INSTANCE;
    static {
        long dc = autoDatacenterId();
        long wk = autoWorkerId(dc);
        INSTANCE = new SnowflakeIdGenerator(dc, wk);
        System.out.printf("[Snowflake] datacenterId=%d, workerId=%d, epoch=%d%n",
                dc, wk, EPOCH);
    }

    public static long getId() {
        return INSTANCE.nextId();
    }

    public static void main(String[] args) {
        // 基准测试
        long start = System.currentTimeMillis();
        int n = 1_000_000;
        for (int i = 0; i < n; i++) {
            getId();
        }
        long cost = System.currentTimeMillis() - start;
        System.out.printf("生成 %d 个 ID 耗时 %d ms，约 %.0f 万/秒%n",
                n, cost, n * 1000.0 / cost);

        long id = getId();
        System.out.println("ID = " + id);
        System.out.println(parse(id));
    }
}
