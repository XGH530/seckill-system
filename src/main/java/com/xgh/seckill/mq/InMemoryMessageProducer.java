package com.xgh.seckill.mq;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 内存消息生产者（无中间件降级方案）
 *
 * 【用途】当你本机没有 RocketMQ / Docker 时，用 JDK 自带的 DelayQueue
 *        模拟"异步下单 + 延迟消息"的完整语义，保证项目开箱即跑。
 *
 * 【面试怎么说】这是"适配器 + 条件装配"的工程实践：
 *   业务代码只依赖 MessageProducer 接口，
 *   生产环境用 RocketMQ 实现，本地/测试环境用内存实现，两者行为一致。
 *   —— 这体现的是"面向接口编程"和"环境可移植性"的工程意识。
 *
 * 与 RocketMQ 的语义差异（要如实说明）：
 *   - 内存队列：单机、进程内、重启即丢，无持久化，无重试
 *   - RocketMQ：分布式、持久化、支持重试与死信、支持事务消息
 *
 * @author 向国豪
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "seckill.mq.rocketmq-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryMessageProducer implements MessageProducer {

    private final MessageConsumer messageConsumer;

    /** JDK 延迟队列：内部用优先队列 + 时间戳实现，到点才可被取出 */
    private final DelayQueue<DelayedMessage> delayQueue = new DelayQueue<>();

    /** 模拟 MQ 的多线程消费 */
    private final ExecutorService consumerPool = Executors.newFixedThreadPool(4);

    private volatile boolean running = true;

    /**
     * 启动消费线程（构造后自动运行）
     */
    public InMemoryMessageProducer(MessageConsumer messageConsumer) {
        this.messageConsumer = messageConsumer;
        // 启动 2 个线程消费延迟队列
        for (int i = 0; i < 2; i++) {
            consumerPool.submit(this::consumeLoop);
        }
        log.warn("[内存MQ] 已启用内置内存队列（模拟 RocketMQ）。" +
                "如需真实 RocketMQ，请设置 seckill.mq.rocketmq-enabled=true");
    }

    /**
     * 消费循环
     */
    private void consumeLoop() {
        while (running) {
            try {
                DelayedMessage msg = delayQueue.take();
                if (msg.isOrderMessage()) {
                    messageConsumer.handleOrderMessage(msg.orderMessage);
                } else {
                    messageConsumer.handleTimeoutMessage(msg.orderMessage);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                // 【重要】消费失败不能让线程退出，否则整个 MQ 就挂了
                log.error("[内存MQ] 消息消费异常", e);
            }
        }
    }

    @Override
    public void sendOrderMessage(SeckillOrderMessage message) {
        // 立即投递（延迟 0）
        delayQueue.offer(new DelayedMessage(message, 0, false));
        log.debug("[内存MQ] 发送下单消息 orderNo={}", message.getOrderNo());
    }

    @Override
    public void sendDelayMessage(SeckillOrderMessage message, long delayMillis) {
        delayQueue.offer(new DelayedMessage(message, delayMillis, true));
        log.info("[内存MQ] 发送延迟消息 orderNo={} 延迟={}ms", message.getOrderNo(), delayMillis);
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        consumerPool.shutdownNow();
        log.info("[内存MQ] 已关闭");
    }

    /**
     * 延迟消息包装
     */
    private static class DelayedMessage implements Delayed {
        private final SeckillOrderMessage orderMessage;
        /** 可被取出的绝对时间点（纳秒） */
        private final long executeTime;
        /** true = 超时检查消息，false = 下单消息 */
        private final boolean timeoutMessage;

        DelayedMessage(SeckillOrderMessage msg, long delayMillis, boolean timeoutMessage) {
            this.orderMessage = msg;
            this.timeoutMessage = timeoutMessage;
            this.executeTime = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        }

        boolean isOrderMessage() {
            return !timeoutMessage;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return unit.convert(executeTime - System.nanoTime(), TimeUnit.NANOSECONDS);
        }

        @Override
        public int compareTo(Delayed other) {
            return Long.compare(this.executeTime, ((DelayedMessage) other).executeTime);
        }
    }
}
