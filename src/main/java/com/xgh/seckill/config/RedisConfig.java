package com.xgh.seckill.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.xgh.seckill.common.constant.SeckillConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置
 *
 * @author 向国豪
 */
@Configuration
public class RedisConfig {

    /**
     * RedisTemplate 序列化配置
     *
     * 【考点 1】为什么必须自定义序列化？
     * 默认 JdkSerializationRedisSerializer 会把 key 序列化成带二进制前缀的乱码
     * （比如 "\xac\xed\x00\x05t\x00\x0bseckill:..."），
     * 在 redis-cli 里根本看不清，也没法用其他语言客户端读取。
     * → key 用 String，value 用 Jackson JSON，可读性最好。
     *
     * 【考点 2】为什么必须注册 JavaTimeModule？
     * Jackson 默认不支持 Java 8 的时间类型（LocalDateTime / LocalDate 等）。
     * 不注册的话，序列化带时间字段的实体（比如 SeckillActivity）会直接抛：
     *   SerializationException: Java 8 date/time type `java.time.LocalDateTime`
     *   not supported by default
     * 这是实际开发中非常常见的坑，涉及 JSR-310 时间 API 时必须记得。
     * → 注册 JavaTimeModule + 关闭时间戳输出（写成 ISO 字符串，便于阅读）
     *
     * 【考点 3】为什么必须开启 activateDefaultTyping？
     * 因为 value 序列化器声明的是 Object.class。
     * 如果不写入类型信息，反序列化时 Jackson 无法知道该还原成什么类，
     * 只能返回 LinkedHashMap，强转成 SeckillActivity 时就会 ClassCastException。
     * → 开启 default typing，在 JSON 中额外写入 "@class" 字段保存真实类型。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // ---------- 构建支持 JSR-310 时间类型的 ObjectMapper ----------
        ObjectMapper objectMapper = new ObjectMapper();
        // 1. 注册 Java 8 时间模块（关键！否则 LocalDateTime 序列化失败）
        objectMapper.registerModule(new JavaTimeModule());
        // 2. 时间写成 ISO-8601 字符串而非时间戳，redis-cli 里可读
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 3. 允许访问私有字段（避免因 getter 缺失导致序列化不全）
        objectMapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 4. 写入类型信息，保证反序列化能还原成原始类型
        objectMapper.activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL);

        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);

        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 【核心难点】Lua 脚本：原子扣减库存
     *
     * 为什么必须用 Lua？
     * Redis 单条命令是原子的，但"判断库存 > 0" + "扣减库存"是两步操作。
     * 如果用 Java 分两次调用：
     *   if (redis.get(stock) > 0) { redis.decr(stock); }   // ← 这里有竞态窗口
     * 1000 个线程可能同时通过 if 判断，全部执行 decr，最终 stock = -999，严重超卖。
     *
     * Lua 脚本在 Redis 中是单线程原子执行的，整个脚本执行期间不会被其他命令打断，
     * 因此"判断 + 扣减"成为一个不可分割的原子操作，从根本上杜绝超卖。
     *
     * 返回值语义：
     *   >= 0 → 扣减成功，返回剩余库存
     *   -1   → 库存不足
     *   -2   → key 不存在（活动未预热或已结束）
     */
    @Bean
    public DefaultRedisScript<Long> seckillStockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/seckill_stock.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 回滚库存 Lua 脚本
     */
    @Bean
    public DefaultRedisScript<Long> rollbackStockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/rollback_stock.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 一人一单原子校验 + 标记
     *
     * 为什么也要用 Lua？
     * "查询用户是否已下单" + "标记用户已下单" 同样是两步操作，
     * 并发下两个请求可能同时通过校验 → 用户重复下单。
     * 用 Lua 合并成一个原子操作即可解决。
     */
    @Bean
    public DefaultRedisScript<Long> userOrderScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/user_order_limit.lua"));
        script.setResultType(Long.class);
        return script;
    }

    /**
     * 原子"取值 + 删除"（GETDEL 的兼容实现）
     *
     * 【考点】为什么需要这个脚本，而不是直接用 getAndDelete()？
     *
     * Redis 6.2 才引入 GETDEL 命令。在 6.2 之前（比如 Windows 移植版
     * 最高只到 5.0.14），调用 getAndDelete() 会报
     * `ERR unknown command 'GETDEL'`。
     *
     * 用 Lua 实现同样语义，兼容所有 Redis 版本：
     *   local v = redis.call('GET', KEYS[1])
     *   if v then redis.call('DEL', KEYS[1]) end
     *   return v
     *
     * 【为什么"取值 + 删除"必须原子】
     * 幂等 Token 的校验就是"取出并删除"。非原子写法下：
     *   两个并发请求都执行 GET 拿到同一个 value → 都判定 Token 有效 → 幂等失效
     * Lua 保证只有一个请求能读到值，另一个读到 nil，从而正确拦截重复提交。
     */
    @Bean
    public DefaultRedisScript<String> getAndDeleteScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/get_and_delete.lua"));
        script.setResultType(String.class);
        return script;
    }

    /**
     * 释放分布式锁（校验持有者，防止误删他人锁）
     *
     * 【考点】为什么不能直接 DEL？
     * 场景：线程 A 持锁但业务执行超时，锁自动过期；
     *      线程 B 随即获取到锁；
     *      此时 A 执行完毕，直接 DEL 就会删掉 B 的锁 → B 的临界区失去保护。
     * 所以必须用 Lua "比较 value 再删除"，保证只删自己的锁。
     */
    @Bean
    public DefaultRedisScript<Long> unlockScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/unlock.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
