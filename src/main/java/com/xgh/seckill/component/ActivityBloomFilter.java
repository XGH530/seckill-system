package com.xgh.seckill.component;

import com.xgh.seckill.common.constant.SeckillConstants;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

/**
 * 活动布隆过滤器 —— 解决缓存穿透的第一道闸门
 *
 * =====================================================================
 * 【为什么需要它？空值缓存不够吗？】
 * =====================================================================
 * 空值缓存（本项目已有）能挡住「重复请求同一个不存在的 ID」，
 * 但它有两个绕不过去的弱点：
 *
 *   1. **挡不住随机 ID 攻击**。攻击者用 seckill:stock:1 → 2 → 3 → ... → 999999
 *      依次请求，每个 ID 都是新的，空值缓存每次都是 MISS，请求全部打到 DB。
 *      这类攻击每个请求都会分配一个缓存 key，还会把 Redis 内存打爆。
 *
 *   2. **有内存代价**。空值缓存要为每个不存在的 ID 存一个 key，
 *      在 10 万级随机 ID 攻击下，Redis 内存会被大量垃圾 key 占满。
 *
 * 布隆过滤器则是：用**固定大小的位数组**，把所有「合法活动 ID」预先标记好。
 * 请求进来先问布隆过滤器「这个 ID 存在吗？」——
 *    - 说不存在  → 100% 确定不存在，直接拒绝，**不查缓存、不查库**
 *    - 说存在    → 可能存在（有误判率），继续走后续流程
 *
 * 这样无论攻击者用多少随机 ID，位数组大小不变，内存占用恒定。
 *
 * =====================================================================
 * 【布隆过滤器的两个特性】
 * =====================================================================
 *   1. **不存在一定准确**（false negative 不会发生）
 *      → 所以「说不存在」时可以安全地直接拒绝请求
 *   2. **存在可能误判**（false positive 会发生）
 *      → 所以「说存在」时不能直接信任，必须继续查缓存/数据库确认
 *
 * 只允许误判为「存在」，不允许误判为「不存在」——这正是它能用来防穿透的原因。
 *
 * =====================================================================
 * 【为什么用 Redisson 的 RBloomFilter 而不是 Guava？】
 * =====================================================================
 *   - Guava 的布隆过滤器只能存在 JVM 内存里，多实例部署时各存一份，
 *     既浪费内存，又会出现「A 实例已预热、B 实例还没预热」的不一致。
 *   - Redisson 的 RBloomFilter 底层是 Redis Bitmap，
 *     天然多实例共享，且支持分布式环境下的并发 add。
 *   - 本项目已经有 Redisson，用它零额外成本。
 *
 * =====================================================================
 * 【已知局限】
 * =====================================================================
 *   - **不支持删除**。位数组里某几位被多个元素共享，删一个元素会把别人的位也清掉。
 *     若活动需要「下线后不再允许访问」，只能靠空值缓存 + 活动状态校验兜底。
 *     真要支持删除，得上 Counting Bloom Filter（每个位用计数器）或 Cuckoo Filter。
 *   - **需要预热**。必须把已存在的活动 ID 提前 add 进去，
 *     否则真实活动会被误判为「不存在」而拒绝。见下方 warmUpAll。
 *
 * @author 向国豪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityBloomFilter {

    private final RedissonClient redissonClient;

    private RBloomFilter<Long> bloomFilter;

    /**
     * 初始化布隆过滤器
     *
     * 【参数含义】
     *   expectedInsertions = 10000  → 预计装 1 万个活动 ID
     *   falseProbability   = 0.01   → 允许 1% 误判率
     *
     * Redisson 会根据这两个参数自动算出需要的位数组长度和哈希函数个数。
     * 粗略地：位数组长度 ≈ -n·ln(p) / (ln2)² ≈ 95851 bit ≈ 12KB。
     * 也就是说，**用 12KB 就能挡住 1 万个随机 ID 的穿透攻击**，这个性价比极高。
     *
     * tryInit 是幂等的：已存在则返回 false，不会重置已有数据。
     */
    @PostConstruct
    public void init() {
        bloomFilter = redissonClient.getBloomFilter(SeckillConstants.BLOOM_ACTIVITY_KEY);
        boolean created = bloomFilter.tryInit(
                SeckillConstants.BLOOM_EXPECTED_INSERTIONS,
                SeckillConstants.BLOOM_FALSE_PROBABILITY);
        log.info("布隆过滤器初始化完成 created={} 预估元素={} 误判率={}",
                created,
                SeckillConstants.BLOOM_EXPECTED_INSERTIONS,
                SeckillConstants.BLOOM_FALSE_PROBABILITY);
    }

    /**
     * 判断活动 ID 是否可能存在
     *
     * @return false = 一定不存在（可直接拒绝）；true = 可能存在（需继续查）
     */
    public boolean mightContain(Long activityId) {
        if (activityId == null || bloomFilter == null) {
            // 布隆过滤器不可用时不能拒绝请求，否则会误杀真实流量
            // 【设计原则】降级时选择「放行」而不是「拒绝」——宁可多查一次库，也不能拒绝正常用户
            return true;
        }
        return bloomFilter.contains(activityId);
    }

    /**
     * 把活动 ID 加入布隆过滤器
     */
    public void add(Long activityId) {
        if (activityId != null && bloomFilter != null) {
            bloomFilter.add(activityId);
        }
    }

    /**
     * 获取当前已插入的元素数量（用于监控）
     */
    public long count() {
        return bloomFilter == null ? 0L : bloomFilter.count();
    }

    /**
     * 判断布隆过滤器是否已初始化
     */
    public boolean isReady() {
        return bloomFilter != null && bloomFilter.isExists();
    }
}
