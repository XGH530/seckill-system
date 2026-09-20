package com.xgh.seckill.service;

import com.xgh.seckill.vo.SeckillResultVO;

/**
 * 秒杀服务
 *
 * @author 向国豪
 */
public interface SeckillService {

    /**
     * 获取秒杀幂等 Token
     * 【设计说明】前端先调本接口拿 Token，下单时带上，
     * 服务端校验 Token 并删除（一次性），防止重复提交/恶意刷单。
     */
    String generateIdempotentToken(Long userId, Long activityId);

    /**
     * 执行秒杀（核心入口）
     *
     * 完整链路：
     *   1. 参数/活动状态校验（含本地缓存）
     *   2. 幂等 Token 校验
     *   3. Redis Lua 原子扣减库存
     *   4. Redis Lua 原子标记一人一单
     *   5. 发送 MQ 消息异步下单（削峰）
     *   6. 立即返回"排队中"
     *
     * @return 秒杀结果（排队中/成功/失败）
     */
    SeckillResultVO doSeckill(Long userId, Long activityId, String idempotentToken);

    /**
     * 查询秒杀结果（前端轮询）
     */
    SeckillResultVO queryResult(Long userId, Long activityId);

    /**
     * 查询当前用户最近的秒杀订单（演示页「我的订单」用）
     */
    java.util.List<com.xgh.seckill.entity.SeckillOrder> listUserOrders(Long userId);

    /**
     * 订单支付（模拟）
     */
    boolean payOrder(Long userId, String orderNo);

    /**
     * 预热活动库存到 Redis
     */
    void warmUpStock(Long activityId);

    /**
     * 批量预热所有活动库存（含布隆过滤器登记）
     *
     * @return 预热成功的活动数量
     */
    int warmUpAll();

    /**
     * 运维：重置活动库存（同时重置 DB、Redis、用户占位）
     *
     * 【为什么需要这个接口】
     * 压测与演示时，库存被抢光后需要重置才能再次验证。
     * 手动改数据库会导致 Redis 与 DB 不一致，进而持续超卖或少卖。
     * 所以必须有一个「三方一起重置」的入口。
     */
    void resetActivityStock(Long activityId, Integer newStock);

    /**
     * 运维：清除活动缓存（用于活动信息变更后强制刷新）
     */
    void clearActivityCache(Long activityId);

    /**
     * 运行时统计与对账（库存一致性检查）
     *
     * 【面试加分点】大部分应届项目只做功能、不做对账。
     * 这个接口把「DB 库存 / Redis 库存 / 有效订单数」三者摆在一起对比，
     * 能在线发现数据不一致，体现生产环境思维。
     */
    java.util.Map<String, Object> runtimeStats();
}
