package com.xgh.seckill.job;

import com.xgh.seckill.common.constant.SeckillConstants;
import com.xgh.seckill.entity.SeckillActivity;
import com.xgh.seckill.entity.SeckillOrder;
import com.xgh.seckill.mapper.SeckillActivityMapper;
import com.xgh.seckill.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

/**
 * 订单超时处理定时任务（MQ 的兜底方案）
 *
 * 【可靠性问题】"如果延迟消息丢了怎么办？"
 * 答：向量：不能只依赖 MQ，必须有兜底。
 *     1. MQ 延迟消息 —— 主路径，实时性好（秒级）
 *     2. 定时任务扫表 —— 兜底路径，保证最终一致（分钟级）
 *     这是"最终一致性"的典型实现：
 *       允许中间状态短暂不一致，但系统最终会收敛到正确状态。
 *
 * 【性能考虑】定时任务扫描要注意：
 *   - 必须走索引：idx_status_expire (status, expire_time)
 *   - 必须限制批次：LIMIT 500，避免一次性捞出几十万条打爆内存
 *   - 集群部署时必须加分布式锁，否则多节点重复处理
 *
 * @author 向国豪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutJob {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Qualifier("rollbackStockScript")
    private final DefaultRedisScript<Long> rollbackStockScript;

    /** 单批处理数量上限 */
    private static final int BATCH_SIZE = 500;

    /**
     * 每 30 秒扫描一次超时订单
     *
     * 【生产环境建议】
     *   - 多实例部署时必须加分布式锁（本项目为单机演示，注释了锁逻辑）
     *   - 用 ShedLock 或 Redisson 实现"同一时刻只有一个节点执行"
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    @Transactional(rollbackFor = Exception.class)
    public void handleTimeoutOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<SeckillOrder> timeoutOrders = orderMapper.selectTimeoutOrders(now, BATCH_SIZE);

        if (timeoutOrders.isEmpty()) {
            return;
        }

        log.info("[定时任务] 发现 {} 个超时未支付订单，开始处理", timeoutOrders.size());
        int success = 0;

        for (SeckillOrder order : timeoutOrders) {
            try {
                // 带 status = 0 条件，保证幂等（即使 MQ 也在处理同一订单）
                int rows = orderMapper.markCanceled(order.getOrderNo());
                if (rows == 0) {
                    continue; // 已被 MQ 消费者处理
                }

                // 回滚 DB 库存
                activityMapper.rollbackStock(order.getActivityId(), 1);

                // 回滚 Redis 库存
                SeckillActivity activity = activityMapper.selectById(order.getActivityId());
                if (activity != null) {
                    redisTemplate.execute(rollbackStockScript,
                            Collections.singletonList(SeckillConstants.STOCK_KEY + order.getActivityId()),
                            String.valueOf(1),
                            String.valueOf(activity.getInitialStock()));
                }

                // 清除用户下单标记，允许重新参与
                redisTemplate.delete(SeckillConstants.USER_ORDER_KEY
                        + order.getActivityId() + ":" + order.getUserId());

                success++;
            } catch (Exception e) {
                log.error("[定时任务] 处理超时订单失败 orderNo={}", order.getOrderNo(), e);
            }
        }

        log.info("[定时任务] 超时订单处理完成 成功={}/{}", success, timeoutOrders.size());
    }

    /**
     * 每 5 分钟执行一次库存对账
     *
     * 【核心价值】这是"最终一致性"的最后保障：
     * 对比"初始库存 - 剩余库存" 与 "有效订单数"，
     * 不相等说明中间环节出了问题（Redis 与 DB 不一致 / 消息丢失），需要人工介入。
     * —— 对账任务用于兜住这类异常，避免库存长时间不一致。
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    public void reconcileStock() {
        List<SeckillActivity> activities = activityMapper.selectList(null);

        for (SeckillActivity activity : activities) {
            Long orderCount = orderMapper.selectCount(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                            .eq(SeckillOrder::getActivityId, activity.getId())
                            .in(SeckillOrder::getStatus,
                                    SeckillConstants.ORDER_STATUS_UNPAID,
                                    SeckillConstants.ORDER_STATUS_PAID));

            int deducted = activity.getInitialStock() - activity.getSeckillStock();

            if (deducted != orderCount.intValue()) {
                log.error("[对账告警] 库存与订单不一致！activityId={} 初始库存={} 剩余库存={} " +
                                "已扣减={} 有效订单数={} 差异={}",
                        activity.getId(), activity.getInitialStock(), activity.getSeckillStock(),
                        deducted, orderCount, deducted - orderCount);
            }
        }
    }
}
