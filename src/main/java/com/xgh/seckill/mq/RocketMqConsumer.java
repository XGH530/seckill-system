package com.xgh.seckill.mq;

import com.xgh.seckill.entity.SeckillOrder;
import com.xgh.seckill.mapper.SeckillActivityMapper;
import com.xgh.seckill.mapper.SeckillOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static com.xgh.seckill.common.constant.SeckillConstants.*;

/**
 * 消息消费者 —— 下单落库 + 超时取消
 *
 * =====================================================================
 * 【核心考点：消费者的三重保障】
 * =====================================================================
 * 1. 幂等（不重复消费）
 *    - 用 messageId 在 Redis 记录已消费标记，重复消息直接丢弃
 *    - 数据库层再叠加 uk_user_activity 唯一索引兜底
 *    - 为什么两道？因为 Redis 可能丢数据（重启未持久化），不能只靠它
 *
 * 2. 不丢消息
 *    - RocketMQ：消费成功才返回 CONSUME_SUCCESS，失败会重试后进死信队列
 *    - 兜底：定时任务扫描超时订单（见 OrderTimeoutJob）
 *
 * 3. 消费失败不能让整个消费者挂掉
 *    - 业务异常向上抛出 → MQ 重试；重试耗尽进死信，人工对账
 *
 * =====================================================================
 * 【第 4 层防线在这里生效：DB 条件更新扣库存】
 * =====================================================================
 * Redis 扣减只是"预扣名额"，真正落实库存要在消费者创建订单时执行
 * UPDATE ... SET seckill_stock = seckill_stock - 1
 * WHERE id = ? AND seckill_stock >= 1
 *
 * 如果返回行数为 0，说明 DB 库存已不足——Redis 与 DB 出现漂移（极端情况，
 * 例如有人绕过 Redis 直接改库）。此时抛异常让事务回滚（订单也不落库），
 * 消息重试；重试耗尽进死信队列，人工对账。
 * 这就是"不信任任何单点"：Redis 说是成功的，DB 也要再验一次。
 *
 * =====================================================================
 * 【重要踩坑：@Transactional 加在 static 方法上完全不生效】
 * =====================================================================
 * 早期版本把处理逻辑写成 static 方法、在 static 方法上标 @Transactional。
 * Spring 事务基于 AOP 代理实现，代理的是"对象实例的方法调用"，
 * static 方法属于类而不属于实例，代理根本拦不到 → 事务静默失效。
 *
 * 教训：注解不是加上了就生效，要理解它背后的实现机制。
 * 因此本类全部改为实例方法，消费者实现类注入本类 Bean 后再调用，
 * 保证 @Transactional 走的是代理调用。
 *
 * @author 向国豪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RocketMqConsumer {

    private final SeckillOrderMapper orderMapper;
    private final SeckillActivityMapper activityMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    @Qualifier("rollbackStockScript")
    private final DefaultRedisScript<Long> rollbackStockScript;

    /** 订单支付超时时间（分钟），与生产者发送延迟消息使用同一配置项 */
    @Value("${seckill.order-timeout-minutes:15}")
    private long orderTimeoutMinutes;

    /**
     * 秒杀下单消息消费者（RocketMQ 实现）
     *
     * 【RocketMQ 注解参数说明】
     * - consumerGroup：消费者组，同组内消息只被消费一次（集群模式）
     * - consumeMode：CONCURRENTLY 并发消费 / ORDERLY 顺序消费
     *   → 秒杀订单之间无顺序依赖，用并发消费，吞吐量优先
     * - maxReconsumeTimes：最大重试次数，超过进死信队列
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(name = "seckill.mq.rocketmq-enabled", havingValue = "true")
    @RocketMQMessageListener(
            topic = TOPIC_SECKILL_ORDER,
            consumerGroup = "seckill-order-consumer-group",
            maxReconsumeTimes = 3
    )
    public static class OrderConsumer implements RocketMQListener<SeckillOrderMessage> {

        private final RocketMqConsumer consumer;

        @Override
        public void onMessage(SeckillOrderMessage message) {
            LOG.info("[MQ消费] 收到下单消息 orderNo={}", message.getOrderNo());
            consumer.handleOrderMessage(message);
        }
    }

    /**
     * 订单超时消费者（延迟消息）
     * 检查订单是否仍为待支付状态，是则取消订单并回滚库存
     */
    @Slf4j
    @Component
    @RequiredArgsConstructor
    @ConditionalOnProperty(name = "seckill.mq.rocketmq-enabled", havingValue = "true")
    @RocketMQMessageListener(
            topic = TOPIC_ORDER_TIMEOUT,
            consumerGroup = "order-timeout-consumer-group",
            maxReconsumeTimes = 3
    )
    public static class TimeoutConsumer implements RocketMQListener<SeckillOrderMessage> {

        private final RocketMqConsumer consumer;

        @Override
        public void onMessage(SeckillOrderMessage message) {
            LOG.info("[MQ消费] 收到超时检查消息 orderNo={}", message.getOrderNo());
            consumer.handleTimeoutMessage(message);
        }
    }

    /**
     * 静态日志对象
     * 【说明】静态内部类无法使用 Lombok 注入的实例字段 log，
     * 因此这里显式声明一个静态 LOG 供内部类使用。
     */
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RocketMqConsumer.class);

    /**
     * 处理下单消息：创建订单 + DB 条件更新扣库存（同一事务）
     *
     * 【事务边界】插入订单与扣减 DB 库存必须在同一事务：
     * 只插订单不扣库存 → 库存少扣（少卖）；只扣库存插单失败 → 库存多扣。
     * 任一失败整体回滚，消息由 MQ 重试。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleOrderMessage(SeckillOrderMessage message) {
        String idemKey = MQ_CONSUMED_KEY + message.getMessageId();

        // ---- 幂等校验 1：Redis 消费标记 ----
        Boolean first = redisTemplate.opsForValue()
                .setIfAbsent(idemKey, "1", MQ_CONSUMED_EXPIRE_HOURS, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(first)) {
            LOG.info("[MQ消费] 重复消息，已忽略 messageId={}", message.getMessageId());
            return;
        }

        try {
            // ---- 幂等校验 2：数据库唯一索引（一人一单）----
            SeckillOrder exist = orderMapper.selectByUserAndActivity(
                    message.getUserId(), message.getActivityId());
            if (exist != null) {
                LOG.info("[MQ消费] 用户已有订单，忽略 userId={} activityId={}",
                        message.getUserId(), message.getActivityId());
                return;
            }

            SeckillOrder order = new SeckillOrder();
            order.setOrderNo(message.getOrderNo());
            order.setUserId(message.getUserId());
            order.setActivityId(message.getActivityId());
            order.setGoodsId(message.getGoodsId());
            order.setSeckillPrice(message.getSeckillPrice());
            order.setStatus(ORDER_STATUS_UNPAID);
            // 支付超时时间：可配置（默认 15 分钟）
            order.setExpireTime(LocalDateTime.now().plusMinutes(orderTimeoutMinutes));

            orderMapper.insert(order);
            LOG.info("[MQ消费] 订单创建成功 orderNo={} userId={}", order.getOrderNo(), order.getUserId());

            // ---- 第 4 层防线：DB 条件更新扣减库存 ----
            // WHERE seckill_stock >= 1 保证并发下不超卖（行锁 + 条件更新）
            int rows = activityMapper.deductStock(message.getActivityId(), 1);
            if (rows == 0) {
                // 正常流程到不了这里（Redis 已挡住售罄），到达即说明 Redis/DB 漂移
                LOG.error("[MQ消费] DB 库存不足！Redis/DB 数据漂移 activityId={} orderNo={}",
                        message.getActivityId(), message.getOrderNo());
                com.xgh.seckill.component.SeckillMetrics m =
                        com.xgh.seckill.component.SeckillMetrics.getInstance();
                if (m != null) {
                    m.recordSystemError();
                }
                // 抛出 → 事务回滚（订单不落库）→ MQ 重试 → 耗尽进死信 → 人工对账
                throw new IllegalStateException(
                        "DB 库存不足，触发第 4 层兜底 activityId=" + message.getActivityId());
            }

            // 【指标埋点】订单真正落库（含 DB 扣减）才算「成单」。
            // 接口层返回"排队中"只是受理成功，不等于成单——
            // 监控里必须把这两个指标分开，否则成功率会虚高。
            com.xgh.seckill.component.SeckillMetrics metrics =
                    com.xgh.seckill.component.SeckillMetrics.getInstance();
            if (metrics != null) {
                metrics.recordSuccess();
            }

        } catch (Exception e) {
            // 插入失败说明幂等标记需要回滚，否则该消息永远不会被重试处理
            redisTemplate.delete(idemKey);
            LOG.error("[MQ消费] 订单创建失败，已回滚幂等标记 messageId={}", message.getMessageId(), e);
            throw e;
        }
    }

    /**
     * 处理超时消息：取消订单 + 回滚 DB 库存 + 回滚 Redis 库存（同一事务内的 DB 部分）
     *
     * 【为什么 Redis 回滚也放在这里】
     * 早期版本 Redis 回滚由调用方（内存 MQ / RocketMQ 两条路径）各自实现，
     * 导致 RocketMQ 路径漏掉了 Redis 回滚。现在统一收敛到本方法，
     * 两条 MQ 路径行为完全一致——消除"逻辑分叉"这类隐蔽 bug。
     *
     * 【失败自愈】若 Redis 回滚脚本执行失败，异常会使 DB 事务一并回滚，
     * 消息重试时整个流程重新执行（markCanceled 带状态条件，天然幂等）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleTimeoutMessage(SeckillOrderMessage message) {
        SeckillOrder order = orderMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SeckillOrder>()
                        .eq(SeckillOrder::getOrderNo, message.getOrderNo()));

        if (order == null) {
            LOG.warn("[超时处理] 订单不存在 orderNo={}", message.getOrderNo());
            return;
        }

        // 只有"待支付"状态才需要取消（已支付/已取消说明是重复的延迟消息）
        if (order.getStatus() != ORDER_STATUS_UNPAID) {
            LOG.info("[超时处理] 订单状态为 {}，无需处理 orderNo={}", order.getStatus(), order.getOrderNo());
            return;
        }

        // 1. 取消订单（带 status = 0 条件，保证幂等）
        int rows = orderMapper.markCanceled(order.getOrderNo());
        if (rows == 0) {
            LOG.info("[超时处理] 订单已被其他线程取消 orderNo={}", order.getOrderNo());
            return;
        }

        // 2. 回滚数据库库存（下单时 deductStock 扣过，这里对称回补；
        //    SQL 带 seckill_stock + 1 <= initial_stock 守卫，防止回补超上限）
        activityMapper.rollbackStock(order.getActivityId(), 1);

        // 3. 回滚 Redis 预扣库存（脚本带回补上限守卫）
        Long result = redisTemplate.execute(rollbackStockScript,
                Collections.singletonList(STOCK_KEY + order.getActivityId()),
                String.valueOf(1),
                String.valueOf(activityMapper.selectById(order.getActivityId()).getInitialStock()));

        LOG.info("[超时处理] 订单已取消并回滚库存(DB+Redis) orderNo={} redisStock={}",
                order.getOrderNo(), result);
    }
}
