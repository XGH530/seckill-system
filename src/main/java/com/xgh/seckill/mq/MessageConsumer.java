package com.xgh.seckill.mq;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 消息消费者统一入口（内存队列实现）
 *
 * 【作用】内存队列模式下的消费入口。真正的业务逻辑全部在
 * RocketMqConsumer 的实例方法中（带事务），本类只做委托——
 * 保证内存 MQ 与 RocketMQ 两种实现下业务行为完全一致（逻辑不分叉）。
 *
 * 【为什么不直接在 InMemoryMessageProducer 里注入 RocketMqConsumer】
 * 保持"生产者 / 消费者入口 / 业务处理器"三层结构清晰：
 * 生产者只管投递，本类只管"收货签收"，业务处理集中在 RocketMqConsumer。
 *
 * @author 向国豪
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageConsumer {

    private final RocketMqConsumer rocketMqConsumer;

    /** 处理下单消息：创建订单 + DB 扣减库存（事务内） */
    public void handleOrderMessage(SeckillOrderMessage message) {
        rocketMqConsumer.handleOrderMessage(message);
    }

    /** 处理超时消息：取消订单 + 回滚 DB/Redis 库存（事务内） */
    public void handleTimeoutMessage(SeckillOrderMessage message) {
        rocketMqConsumer.handleTimeoutMessage(message);
    }
}
