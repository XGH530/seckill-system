package com.xgh.seckill.common.constant;

/**
 * Redis Key 与业务常量统一定义
 * 【工程规范】集中管理，避免 key 散落各处导致命名冲突和难以排查
 *
 * @author 向国豪
 */
public final class SeckillConstants {

    private SeckillConstants() {
    }

    // ==================== Redis Key 前缀 ====================

    /** 秒杀库存：seckill:stock:{activityId} → String（数值） */
    public static final String STOCK_KEY = "seckill:stock:";

    /** 秒杀活动信息缓存：seckill:activity:{activityId} → Hash */
    public static final String ACTIVITY_KEY = "seckill:activity:";

    /** 商品信息缓存：seckill:goods:{goodsId} → Hash */
    public static final String GOODS_KEY = "seckill:goods:";

    /** 用户下单标记（一人一单）：seckill:order:user:{activityId}:{userId} → 订单号 */
    public static final String USER_ORDER_KEY = "seckill:order:user:";

    /** 空值缓存（解决缓存穿透）：seckill:null:{key} */
    public static final String NULL_CACHE_KEY = "seckill:null:";
    /** 布隆过滤器（防缓存穿透）：seckill:bloom:activity */
    public static final String BLOOM_ACTIVITY_KEY = "seckill:bloom:activity";
    /** 布隆过滤器预估元素数量与误判率（决定位数组大小和哈希次数） */
    public static final long BLOOM_EXPECTED_INSERTIONS = 10000L;
    public static final double BLOOM_FALSE_PROBABILITY = 0.01d;

    /** 分布式锁 Key：seckill:lock:{businessKey} */
    public static final String LOCK_KEY = "seckill:lock:";

    /** 接口幂等 Token：seckill:idem:token:{token} */
    public static final String IDEMPOTENT_TOKEN_KEY = "seckill:idem:token:";

    /** 秒杀结果缓存（用于异步下单后前端轮询）：seckill:result:{activityId}:{userId} */
    public static final String SECKILL_RESULT_KEY = "seckill:result:";

    /** 消息消费幂等：seckill:mq:consumed:{messageId} */
    public static final String MQ_CONSUMED_KEY = "seckill:mq:consumed:";

    // ==================== 时效 ====================

    /** 空值缓存过期时间：60 秒（防缓存穿透，不能太长以免数据恢复了还返回空） */
    public static final long NULL_CACHE_EXPIRE_SECONDS = 60L;

    /** 活动缓存基础过期时间：30 分钟 */
    public static final long ACTIVITY_CACHE_EXPIRE_MINUTES = 30L;

    /** 活动缓存随机抖动上限（分钟）——【解决缓存雪崩】让过期时间分散 */
    public static final long ACTIVITY_CACHE_RANDOM_BOUND = 10L;

    /** 秒杀结果缓存过期：5 分钟（够前端轮询） */
    public static final long SECKILL_RESULT_EXPIRE_SECONDS = 300L;

    /** 消息消费幂等记录过期：24 小时 */
    public static final long MQ_CONSUMED_EXPIRE_HOURS = 24L;

    /** 幂等 Token 过期：10 分钟 */
    public static final long IDEMPOTENT_TOKEN_EXPIRE_MINUTES = 10L;

    // ==================== 业务常量 ====================

    /** 秒杀活动状态 */
    public static final int ACTIVITY_STATUS_NOT_START = 0;
    public static final int ACTIVITY_STATUS_RUNNING = 1;
    public static final int ACTIVITY_STATUS_ENDED = 2;

    /** 订单状态 */
    public static final int ORDER_STATUS_UNPAID = 0;
    public static final int ORDER_STATUS_PAID = 1;
    public static final int ORDER_STATUS_CANCELED = 2;
    public static final int ORDER_STATUS_REFUNDED = 3;

    /** RocketMQ Topic */
    public static final String TOPIC_SECKILL_ORDER = "seckill-order-topic";
    public static final String TOPIC_ORDER_TIMEOUT = "order-timeout-topic";
    public static final String TOPIC_STOCK_ROLLBACK = "stock-rollback-topic";
}
