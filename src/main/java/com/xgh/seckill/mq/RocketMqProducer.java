package com.xgh.seckill.mq;

import com.xgh.seckill.common.util.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

import static com.xgh.seckill.common.constant.SeckillConstants.TOPIC_SECKILL_ORDER;
import static com.xgh.seckill.common.constant.SeckillConstants.TOPIC_ORDER_TIMEOUT;

/**
 * RocketMQ 消息生产者
 *
 * 通过 seckill.mq.rocketmq-enabled 开关控制是否启用：
 *  - true  → 使用真实 RocketMQ（需要部署 NameServer + Broker）
 *  - false → 使用 {@link InMemoryMessageProducer} 内存队列（本地一键演示）
 *
 * 【设计说明】用接口 + 条件装配实现"可插拔的 MQ"，
 * 既保证生产配置下使用真实 RocketMQ，又让项目在任何机器上都能跑起来。
 *
 * @author 向国豪
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "seckill.mq.rocketmq-enabled", havingValue = "true")
public class RocketMqProducer implements MessageProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final SnowflakeIdGenerator idGenerator;

    @Value("${seckill.order-timeout-minutes:15}")
    private int orderTimeoutMinutes;

    /**
     * 发送秒杀下单消息（普通消息）
     */
    @Override
    public void sendOrderMessage(SeckillOrderMessage message) {
        rocketMQTemplate.syncSend(TOPIC_SECKILL_ORDER,
                MessageBuilder.withPayload(message).build());
        log.info("[MQ发送] 秒杀订单消息 messageId={} orderNo={}", message.getMessageId(), message.getOrderNo());
    }

    /**
     * 发送延迟消息（订单超时检查）
     *
     * 【核心考点】RocketMQ 延迟消息的 delayLevel 与时间对应关系：
     *   1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h
     *   对应 level 1 ~ 18
     *
     * RocketMQ 4.x 只支持固定 18 个等级，不支持任意时间。
     * 本项目支付超时 15 分钟 → 选 level 14（10 分钟）+ level 6（2 分钟）组合，
     * 或直接用 level 15（20 分钟）后由业务二次校验 expireTime。
     *
     * RocketMQ 5.x 支持任意时间延迟（定时消息），写法为：
     *   rocketMQTemplate.syncSendDelayTimeMills(topic, msg, delayMillis)
     */
    @Override
    public void sendDelayMessage(SeckillOrderMessage message, long delayMillis) {
        int delayLevel = convertToDelayLevel(delayMillis);
        rocketMQTemplate.syncSend(TOPIC_ORDER_TIMEOUT,
                MessageBuilder.withPayload(message).build(), 3000, delayLevel);
        log.info("[MQ发送] 延迟消息 orderNo={} delayLevel={}", message.getOrderNo(), delayLevel);
    }

    /**
     * 将毫秒转换为 RocketMQ 延迟等级
     */
    private int convertToDelayLevel(long delayMillis) {
        long[] levels = {1000L, 5000L, 10000L, 30000L, 60000L, 120000L, 180000L, 240000L,
                300000L, 360000L, 420000L, 480000L, 540000L, 600000L, 1200000L,
                1800000L, 3600000L, 7200000L};
        for (int i = 0; i < levels.length; i++) {
            if (delayMillis <= levels[i]) {
                return i + 1;
            }
        }
        return 18;
    }
}
