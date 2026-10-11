package com.xgh.seckill.common.util;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 雪花算法（Snowflake）分布式 ID 生成器
 *
 * 【说明：ID 的位结构】
 *
 * 64 bit 组成（1 + 41 + 10 + 12）：
 * <pre>
 *   0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 00000 - 000000000000
 *   ↑   ↑                                              ↑              ↑
 *   │   └────────── 41 bit 时间戳 ────────────────────┘              │
 *   │               (相对自定义纪元的毫秒数，可用 69 年)               │
 *   │                                                                │
 *   └─ 1 bit 符号位（恒为 0，保证 ID 为正数）        12 bit 序列号 ────┘
 *                                                    同一毫秒内可生成 4096 个
 *                        ↑
 *              10 bit 机器 ID（5 bit 数据中心 + 5 bit 工作机器）
 *              最多 1024 台机器
 * </pre>
 *
 * 优点：本地生成、无网络开销、趋势递增（对 MySQL B+ 树索引友好）
 * 缺点：依赖系统时钟，时钟回拨会导致 ID 重复
 *
 * 【时钟回拨处理】本项目采取的策略：
 *   1. 回拨 < 5ms：自旋等待，直到时间追上
 *   2. 回拨 >= 5ms：抛出异常并告警（生产环境可考虑：改用备用机器ID / 等待 / 直接报错）
 *
 * @author 向国豪
 */
@Slf4j
@Component
public class SnowflakeIdGenerator {

    /** 起始纪元：2024-01-01 00:00:00 UTC，可用约 69 年 */
    private static final long EPOCH = 1704067200000L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);          // 31
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);  // 31
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);           // 4095

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;                                          // 12
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;                     // 17
    private static final long TIMESTAMP_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;    // 22

    /** 允许的最大时钟回拨容忍度（毫秒） */
    private static final long MAX_BACKWARD_MS = 5L;

    @Value("${seckill.snowflake.worker-id:1}")
    private long workerId;

    @Value("${seckill.snowflake.datacenter-id:1}")
    private long datacenterId;

    private long sequence = 0L;
    private long lastTimestamp = -1L;

    @PostConstruct
    public void init() {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId 必须在 0 ~ " + MAX_WORKER_ID + " 之间");
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId 必须在 0 ~ " + MAX_DATACENTER_ID + " 之间");
        }
        log.info("雪花算法初始化完成 workerId={} datacenterId={}", workerId, datacenterId);
    }

    /**
     * 生成下一个 ID（线程安全）
     */
    public synchronized long nextId() {
        long currentTimestamp = currentTime();

        // ---- 时钟回拨处理 ----
        if (currentTimestamp < lastTimestamp) {
            long offset = lastTimestamp - currentTimestamp;
            if (offset <= MAX_BACKWARD_MS) {
                // 轻微回拨：自旋等待
                log.warn("检测到时钟回拨 {}ms，自旋等待中", offset);
                while (currentTimestamp < lastTimestamp) {
                    currentTimestamp = currentTime();
                }
            } else {
                // 严重回拨：拒绝生成，防止 ID 重复
                throw new IllegalStateException(
                        "时钟回拨过大，拒绝生成ID。回拨 " + offset + "ms，超过阈值 " + MAX_BACKWARD_MS + "ms");
            }
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒内：序列号自增
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                // 该毫秒的 4096 个序列号用完，等待下一毫秒
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒：序列号归零
            // 【优化】不用固定 0，而是用随机数，降低多机冲突概率
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    /**
     * 生成带业务前缀的订单号
     * 格式：SK + 雪花ID（便于通过订单号一眼识别业务类型）
     */
    public String nextOrderNo() {
        return "SK" + nextId();
    }

    private long waitNextMillis(long lastTimestamp) {
        long timestamp = currentTime();
        while (timestamp <= lastTimestamp) {
            timestamp = currentTime();
        }
        return timestamp;
    }

    private long currentTime() {
        return System.currentTimeMillis();
    }
}
