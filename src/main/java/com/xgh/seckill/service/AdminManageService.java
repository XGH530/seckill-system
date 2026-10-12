package com.xgh.seckill.service;

import java.util.List;
import java.util.Map;

/**
 * 后台管理控制台服务
 *
 * 【设计说明】
 * 与 {@link SeckillService} 分开:后者是"用户抢购"的业务链路(高并发、只关心自己的单),
 * 这里是一组"运营视角"的接口 —— 看全量活动/商品/订单/用户,并能干预异常数据。
 * 所以单独一个 Service,避免把管理端逻辑混进核心秒杀链路里。
 *
 * @author 向国豪
 */
public interface AdminManageService {

    /** 控制台概览:活动/商品/用户/订单总量 + 库存汇总 + 实时指标 */
    Map<String, Object> overview();

    /** 活动列表:DB 库存 / Redis 库存 / 有效订单数 三列并排,便于在线对账 */
    List<Map<String, Object>> activities();

    /** 上架 / 下架活动(0-未开始,1-进行中,2-已结束) */
    void updateActivityStatus(Long activityId, Integer status);

    /** 订单分页查询 */
    Map<String, Object> orders(long current, long size, Integer status, Long activityId);

    /** 人工关单:取消订单 + 回补 DB/Redis 库存 + 释放"一人一单"占位(幂等) */
    boolean cancelOrder(String orderNo);

    /** 用户分页查询 */
    Map<String, Object> users(long current, long size, String keyword);

    /** 商品列表 */
    List<Map<String, Object>> goods();

    /** 新增商品 */
    Long createGoods(Map<String, Object> body);

    /** 修改商品 */
    void updateGoods(Long id, Map<String, Object> body);

    /** 删除商品(逻辑删除) */
    void deleteGoods(Long id);

    /** 运行时健康检查:数据库 / Redis / JVM */
    Map<String, Object> health();
}