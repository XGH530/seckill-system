package com.xgh.seckill.aspect;

import com.xgh.seckill.annotation.DistributedLock;
import com.xgh.seckill.common.exception.BizException;
import com.xgh.seckill.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 分布式锁切面
 *
 * 【核心职责】把 @DistributedLock 注解翻译成 Redisson 的加锁/释放动作，
 * 并把 SpEL 表达式解析成具体的锁 key。
 *
 * 【为什么用 AOP 而不是显式 lock/unlock】
 * 显式写法一旦忘记 try-finally，异常时就会死锁；AOP 统一处理，业务代码保持干净。
 *
 * @author 向国豪
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class DistributedLockAspect {

    private final RedissonClient redissonClient;

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * JVM 级本地锁表（可重入计数）。
     *
     * 【为什么用 ConcurrentHashMap + AtomicInteger 而不是 ReentrantLock？】
     * 因为本地锁只需要「互斥 + 可重入 + 快速失败」，不需要阻塞排队。
     * 用计数式实现可以避免大量线程在本地排队（那样只是把阻塞从 Redis 挪到了 JVM，
     * 反而更容易把 Tomcat 线程池占满）。
     *
     * 【内存泄漏风险】如果 putIfAbsent 后一直不 remove，key 会无限增长。
     * 所以 finally 中必须在计数归零时移除——见下方 finally 块。
     */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> localLocks
            = new ConcurrentHashMap<>();

    @Around("@annotation(distributedLock)")
    public Object around(ProceedingJoinPoint joinPoint, DistributedLock distributedLock) throws Throwable {
        // 1. 解析 SpEL 表达式，生成锁 key
        String lockKey = distributedLock.prefix() + resolveKey(joinPoint, distributedLock.key());

        // 2. 本地双重检查（可选）：先在 JVM 内抢一次，抢不到就直接失败，不碰 Redis
        boolean localAcquired = false;
        if (distributedLock.localLock()) {
            localAcquired = tryLocalLock(lockKey);
            if (!localAcquired) {
                log.debug("本地锁未获取到，快速失败 key={}", lockKey);
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, distributedLock.failMessage());
            }
        }

        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        long start = System.currentTimeMillis();

        try {
            // 3. 尝试加分布式锁
            if (distributedLock.leaseTime() > 0) {
                // 指定了租期 → 看门狗不生效，锁到期必定释放（需要业务自己保证在租期内完成）
                acquired = lock.tryLock(distributedLock.waitTime(),
                        distributedLock.leaseTime(), TimeUnit.MILLISECONDS);
            } else {
                // 未指定租期（-1）→ 启用看门狗自动续期
                acquired = lock.tryLock(distributedLock.waitTime(), TimeUnit.MILLISECONDS);
            }

            if (!acquired) {
                log.warn("获取分布式锁失败 key={} 等待={}ms", lockKey, distributedLock.waitTime());
                throw new BizException(ResultCode.LOCK_ACQUIRE_FAIL, distributedLock.failMessage());
            }

            log.debug("获取分布式锁成功 key={} 耗时={}ms", lockKey, System.currentTimeMillis() - start);

            // 4. 执行业务
            return joinPoint.proceed();

        } finally {
            // 5. 释放分布式锁（判断持有者是当前线程，防止误删他人锁）
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放分布式锁 key={} 业务耗时={}ms", lockKey, System.currentTimeMillis() - start);
            }
            // 6. 释放本地锁（必须放在分布式锁之后，保证临界区完整覆盖）
            if (localAcquired) {
                releaseLocalLock(lockKey);
            }
        }
    }

    /**
     * 尝试获取本地锁（非阻塞，拿不到立即返回 false）
     */
    private boolean tryLocalLock(String lockKey) {
        java.util.concurrent.atomic.AtomicInteger counter =
                localLocks.computeIfAbsent(lockKey, k -> new java.util.concurrent.atomic.AtomicInteger(0));
        while (true) {
            int cur = counter.get();
            if (cur != 0) {
                // 已有线程持有 → 快速失败
                return false;
            }
            if (counter.compareAndSet(0, 1)) {
                return true;
            }
        }
    }

    /**
     * 释放本地锁，并在计数归零时移除 key，避免 map 无限增长
     */
    private void releaseLocalLock(String lockKey) {
        localLocks.computeIfPresent(lockKey, (k, counter) -> {
            counter.decrementAndGet();
            return counter.get() <= 0 ? null : counter;
        });
    }

    /**
     * 解析 SpEL 表达式
     */
    private String resolveKey(ProceedingJoinPoint joinPoint, String spEL) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Object[] args = joinPoint.getArgs();

        String[] paramNames = NAME_DISCOVERER.getParameterNames(method);
        EvaluationContext context = new StandardEvaluationContext();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        Object value = PARSER.parseExpression(spEL).getValue(context);
        return value == null ? "null" : value.toString();
    }
}
