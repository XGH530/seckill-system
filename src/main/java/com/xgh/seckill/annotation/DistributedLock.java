package com.xgh.seckill.annotation;

import java.lang.annotation.*;

/**
 * 分布式锁注解
 *
 * 用法示例：
 * <pre>
 *   {@code @DistributedLock(prefix = "seckill:lock:order", key = "#userId + ':' + #activityId")}
 *   public Result<String> doSeckill(Long userId, Long activityId) { ... }
 * </pre>
 *
 * 【设计说明】为什么用注解而不是显式 lock/unlock？
 * - 显式写法容易忘记 try-finally 释放锁，一旦异常就会死锁
 * - 注解方式由 AOP 统一处理加锁/释放，业务代码保持干净
 * - 这也是 Redisson 官方推荐 + 生产环境常见的做法
 *
 * @author 向国豪
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DistributedLock {

    /** 锁 key 前缀，如 seckill:lock:order */
    String prefix() default "seckill:lock:";

    /**
     * SpEL 表达式，用于生成锁的 key 后缀
     * 支持引用方法参数名，如 "#userId"、"'A' + #userId"
     */
    String key();

    /** 获取锁的最大等待时间（毫秒），超时则抛异常 */
    long waitTime() default 3000;

    /**
     * 锁自动释放时间（毫秒）
     * 【重要】如果这里设置了值，Redisson 的看门狗续期机制会失效！
     * 因为看门狗只在 leaseTime = -1（不指定）时才启用。
     * 本项目为了演示看门狗，默认传 -1。
     */
    long leaseTime() default -1;

    /** 获取锁失败时的提示信息 */
    String failMessage() default "系统繁忙，请稍后重试";

    /**
     * 开启「本地双重检查」优化（默认关闭）
     *
     * 【解决什么问题】高频抢购下，同一个用户可能瞬间发出多次请求，
     * 每个请求都去抢 Redisson 锁，会给 Redis 带来大量无谓的加锁/等锁往返。
     *
     * 【做法】在 JVM 内先用 ConcurrentHashMap 按锁 key 记一个「本地锁」，
     * 本地拿不到的请求直接快速失败，根本不碰 Redis。
     * 只有当本地锁拿到手时，才去抢分布式锁——这把并发压力从 Redis 前置到了应用内存。
     *
     * 【注意】本地锁只对「同一台机器上的同一把 key」有效，
     * 跨实例的正确性仍然由 Redisson 分布式锁保证。所以它是**性能优化**，不是正确性依赖。
     * 这正是本项目一以贯之的原则：不信任单点，但可以合理利用本地资源提性能。
     */
    boolean localLock() default false;
}
