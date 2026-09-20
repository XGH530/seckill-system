package com.xgh.seckill.component;

import com.xgh.seckill.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 秒杀业务指标采集器
 *
 * =====================================================================
 * 【为什么需要它？—— 面试必问「你怎么知道系统健康」】
 * =====================================================================
 * 压测跑完之后，你总得回答三个问题：
 *   1. 有多少请求成功了？（而不是靠人工数日志）
 *   2. 失败的都是什么原因？（售罄 / 重复 / 限流 / 锁竞争，各占多少）
 *   3. 系统自己有没有出异常？（区别于「业务失败」）
 *
 * 这个类用 LongAdder 在内存里做计数，跑完压测调一次 /admin/metrics 就能拿到全量分布。
 *
 * =====================================================================
 * 【为什么用 LongAdder 而不是 AtomicLong？】
 * =====================================================================
 *   AtomicLong 是「单个变量 + CAS 自旋」。在高并发下，成千上万个线程同时 CAS
 *   同一个变量，会大量失败重试，CPU 空转严重（伪共享 + 缓存行争抢）。
 *
 *   LongAdder 的思路是「空间换时间」：
 *     内部维护一个 Cell 数组，不同线程分散到不同 Cell 上累加，
 *     读取时再把所有 Cell 求和。写操作热点被打散，吞吐量能高一个数量级。
 *
 *   代价是：sum() 返回的不是强一致的瞬时值（并发写入时可能略滞后）。
 *   但对「统计指标」这个场景，弱一致完全可以接受——我们不需要精确到某一次写。
 *
 *   【这就是典型的「按场景选数据结构」】面试时能讲清 LongAdder 与 AtomicLong 的
 *   取舍，比只会背「AtomicLong 是 CAS」要加分得多。
 *
 * =====================================================================
 * 【局限：单机内存，不是分布式指标】
 * =====================================================================
 *   真实生产环境应该用 Micrometer + Prometheus：
 *     - 多实例指标自动聚合
 *     - 支持 QPS / P99 分位数计算
 *     - 可接 Grafana 看板与告警
 *   本项目用内存计数是为了零依赖、可离线演示。
 *   **主动说出这个局限，比假装它是生产级方案要好。**
 *
 * @author 向国豪
 */
@Slf4j
@Component
public class SeckillMetrics {

    /** 请求总数（进入秒杀接口的所有请求） */
    private final LongAdder totalRequests = new LongAdder();

    /** 成功受理（返回"排队中"，最终由 MQ 落单） */
    private final LongAdder queuedCount = new LongAdder();

    /** 最终成功生成订单数（消费端落库成功后才 +1） */
    private final LongAdder successCount = new LongAdder();

    /** 各类失败原因计数：业务码 → 次数 */
    private final Map<String, LongAdder> failureByReason = new ConcurrentHashMap<>();

    /** 系统异常（区别于业务失败：这是"我们自己的锅"） */
    private final LongAdder systemErrorCount = new LongAdder();

    /** 累计处理耗时（用于算平均 RT） */
    private final LongAdder totalCostMillis = new LongAdder();

    /** 耗时分布直方图：用于估算 P95 / P99 */
    private final long[] LATENCY_BUCKETS = {5, 10, 20, 50, 100, 200, 500, 1000, Long.MAX_VALUE};
    private final LongAdder[] latencyHistogram = new LongAdder[LATENCY_BUCKETS.length];

    /** 服务启动时间戳 */
    private final long startTimeMillis = System.currentTimeMillis();

    /**
     * 静态实例引用
     *
     * 【为什么需要这个？】MQ 消费端的业务逻辑写成了 static 方法
     * （为了内存队列实现和 RocketMQ 实现共用同一套代码），
     * 静态方法拿不到 Spring 注入的实例字段。
     *
     * 【为什么不用 SpringContextHolder 之类的工具？】
     * 那会引入对 ApplicationContext 的隐式依赖，单测时难以构造。
     * 这里用「Bean 初始化时把自己注册到静态字段」的方式，
     * 简单直白，且在单测里可以手动 setInstance 注入 mock。
     *
     * 【注意】这是为了适配 static 方法做的妥协。
     * 更干净的做法是把消费逻辑改成实例方法 + 抽成 Service，
     * 但那样要改动 MQ 双实现的共享结构，收益不值当。
     * 面试如果被问到，如实说明这个取舍即可。
     */
    private static volatile SeckillMetrics instance;

    public SeckillMetrics() {
        for (int i = 0; i < latencyHistogram.length; i++) {
            latencyHistogram[i] = new LongAdder();
        }
        instance = this;
    }

    /**
     * 获取静态实例（供 MQ 消费端 static 方法调用）
     *
     * 【为什么返回 Optional 风格而不是直接 new？】
     * 因为指标是「尽力而为」的观测设施，绝不能因为它不可用而让业务失败。
     * 所有调用点都必须容忍 instance == null（如在非 Spring 环境跑单测）。
     */
    public static SeckillMetrics getInstance() {
        return instance;
    }

    // =================================================================
    // 埋点方法（在业务链路中调用）
    // =================================================================

    /**
     * 记录一次请求进入
     */
    public void recordRequest() {
        totalRequests.increment();
    }

    /**
     * 记录一次成功受理（排队中）
     */
    public void recordQueued() {
        queuedCount.increment();
    }

    /**
     * 记录最终订单落库成功
     */
    public void recordSuccess() {
        successCount.increment();
    }

    /**
     * 记录一次业务失败（按错误码分类）
     */
    public void recordFailure(ResultCode code) {
        String key = code == null ? "UNKNOWN" : code.getCode() + ":" + code.getMessage();
        failureByReason.computeIfAbsent(key, k -> new LongAdder()).increment();
    }

    /**
     * 记录一次业务失败（按自定义原因，如锁竞争失败）
     */
    public void recordFailure(String reason) {
        failureByReason.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    /**
     * 记录一次系统异常
     */
    public void recordSystemError() {
        systemErrorCount.increment();
    }

    /**
     * 记录请求耗时（毫秒），同时写入直方图
     */
    public void recordLatency(long millis) {
        totalCostMillis.add(millis);
        for (int i = 0; i < LATENCY_BUCKETS.length; i++) {
            if (millis <= LATENCY_BUCKETS[i]) {
                latencyHistogram[i].increment();
                break;
            }
        }
    }

    // =================================================================
    // 指标快照
    // =================================================================

    /**
     * 生成当前指标快照（给 /admin/metrics 接口用）
     */
    public Map<String, Object> snapshot() {
        long total = totalRequests.sum();
        long queued = queuedCount.sum();
        long success = successCount.sum();
        long systemErr = systemErrorCount.sum();
        long failures = failureByReason.values().stream().mapToLong(LongAdder::sum).sum();

        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("uptimeSeconds", (System.currentTimeMillis() - startTimeMillis) / 1000);
        snap.put("uptimeText", formatUptime((System.currentTimeMillis() - startTimeMillis) / 1000));

        // ---- 请求量 ----
        Map<String, Object> volume = new LinkedHashMap<>();
        volume.put("totalRequests", total);
        volume.put("queued", queued);
        volume.put("orderCreated", success);
        volume.put("businessFailed", failures);
        volume.put("systemError", systemErr);
        snap.put("volume", volume);

        // ---- 成功率 ----
        // 【注意】这里的成功率要区分两种口径，面试常被追问：
        //   1. 受理成功率 = queued / total  → 反映接口层过滤效果
        //   2. 成单率     = orderCreated / total → 反映最终履约效果
        // 秒杀场景下成单率天然很低（库存有限），这是正常的，不能当作系统故障。
        Map<String, Object> rate = new LinkedHashMap<>();
        rate.put("acceptRate", total == 0 ? 0d : round(queued * 100.0 / total));
        rate.put("orderRate", total == 0 ? 0d : round(success * 100.0 / total));
        rate.put("systemErrorRate", total == 0 ? 0d : round(systemErr * 100.0 / total));
        snap.put("rate", rate);

        // ---- 延迟 ----
        Map<String, Object> latency = new LinkedHashMap<>();
        latency.put("avgMs", total == 0 ? 0d : round(totalCostMillis.sum() * 1.0 / total));
        latency.put("p95Ms", estimatePercentile(0.95));
        latency.put("p99Ms", estimatePercentile(0.99));
        snap.put("latency", latency);

        // ---- 失败原因分布（这是最有价值的部分） ----
        Map<String, Long> reasons = new LinkedHashMap<>();
        failureByReason.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .forEach(e -> reasons.put(e.getKey(), e.getValue().sum()));
        snap.put("failureReasons", reasons);

        return snap;
    }

    /**
     * 用直方图估算分位数
     *
     * 【为什么是"估算"？】
     * 精确 P99 需要保存每一个请求的耗时并排序，内存代价 O(n)。
     * 直方图只记录「落在每个桶里的数量」，内存固定，
     * 用桶上界近似真实值——对性能监控来说精度足够。
     * 生产级方案（如 Prometheus Histogram）也是同样的思路。
     *
     * 【估算方法】找到累计数量首次超过目标分位数的桶，返回该桶的上界。
     */
    private long estimatePercentile(double percentile) {
        long total = 0;
        for (LongAdder h : latencyHistogram) {
            total += h.sum();
        }
        if (total == 0) {
            return 0;
        }
        long target = (long) Math.ceil(total * percentile);
        long cumulative = 0;
        for (int i = 0; i < latencyHistogram.length; i++) {
            cumulative += latencyHistogram[i].sum();
            if (cumulative >= target) {
                return LATENCY_BUCKETS[i] == Long.MAX_VALUE ? LATENCY_BUCKETS[i - 1] : LATENCY_BUCKETS[i];
            }
        }
        return LATENCY_BUCKETS[LATENCY_BUCKETS.length - 1];
    }

    /**
     * 重置所有指标（压测前调用，保证每轮数据独立）
     */
    public void reset() {
        totalRequests.reset();
        queuedCount.reset();
        successCount.reset();
        successCount.reset();
        systemErrorCount.reset();
        totalCostMillis.reset();
        failureByReason.clear();
        for (LongAdder h : latencyHistogram) {
            h.reset();
        }
        log.info("业务指标已重置");
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static String formatUptime(long seconds) {
        long h = seconds / 3600, m = seconds % 3600 / 60, s = seconds % 60;
        return String.format("%dh %dm %ds", h, m, s);
    }
}
