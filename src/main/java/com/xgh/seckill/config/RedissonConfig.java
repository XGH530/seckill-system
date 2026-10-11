package com.xgh.seckill.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置
 *
 * 【设计说明】为什么用 Redisson 而不是自己 SETNX？
 *
 * 1. 自己实现 SETNX 的三大坑：
 *    a) 锁误删 —— 需要 Lua 校验 value（本项目的 unlock.lua 就是示范）
 *    b) 业务超时导致锁提前释放 —— 需要"看门狗"自动续期
 *    c) 不可重入 —— 同一线程重复加锁会死锁
 *
 * 2. Redisson 全部解决了：
 *    - 可重入：用 Hash 结构存 {线程标识: 重入次数}
 *    - 看门狗（watchDog）：默认锁 30s，后台线程每 10s（1/3 周期）续期一次，
 *      只要业务没结束锁就不会过期；业务宕机则停止续期，锁自然到期释放
 *    - 锁误删：Lua 脚本保证"比较 + 删除"原子
 *
 * 3. Redisson 仍未解决的问题：
 *    - 主从架构下：主节点加锁成功后宕机，从节点尚未同步 → 锁丢失 → 另一线程可加锁
 *    - 解决：RedLock 算法（多数派加锁）或改用 ZooKeeper（CP 模型）
 *    - 但 RedLock 也有争议（Martin Kleppmann vs antirez 的经典辩论）
 *
 * 【本项目实践】秒杀扣库存的"最终防线"仍放在数据库条件更新上，
 * 分布式锁只用于减少 DB 压力的辅助手段 —— 这叫"不信任任何单点"。
 *
 * @author 向国豪
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.data.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    @Value("${spring.data.redis.database:0}")
    private int database;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(10)
                .setConnectionPoolSize(64)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        return Redisson.create(config);
    }
}
