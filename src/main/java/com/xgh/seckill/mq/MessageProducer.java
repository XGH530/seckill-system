package com.xgh.seckill.mq;

/**
 * 消息生产者抽象
 *
 * 【设计模式考点】这里体现的是「依赖倒置」+「策略模式」：
 * 业务层只依赖 MessageProducer 接口，不关心底层是 RocketMQ 还是内存队列。
 * 好处：
 *   1. 测试时可注入 Mock 实现
 *   2. 中间件可替换（未来换 Kafka 只需新增一个实现类）
 *   3. 无中间件环境下也能演示完整业务链路
 *
 * @author 向国豪
 */
public interface MessageProducer {

    /**
     * 发送秒杀下单消息
     */
    void sendOrderMessage(SeckillOrderMessage message);

    /**
     * 发送延迟消息（用于订单超时回滚）
     *
     * @param message     消息体
     * @param delayMillis 延迟毫秒数
     */
    void sendDelayMessage(SeckillOrderMessage message, long delayMillis);
}
