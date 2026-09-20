package com.xgh.seckill.common.result;

import lombok.Getter;

/**
 * 统一响应状态码
 *
 * @author 向国豪
 */
@Getter
public enum ResultCode {

    SUCCESS(200, "操作成功"),

    // ===== 400 段：参数与业务校验 =====
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录或登录已过期"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "资源不存在"),

    // ===== 500 段：系统异常 =====
    SYSTEM_ERROR(500, "系统繁忙，请稍后重试"),

    // ===== 600 段：秒杀业务专属 =====
    SECKILL_NOT_START(600, "秒杀活动还未开始"),
    SECKILL_ENDED(601, "秒杀活动已结束"),
    SECKILL_STOCK_EMPTY(602, "商品已售罄"),
    SECKILL_REPEAT(603, "您已参与过本次秒杀，请勿重复下单"),
    SECKILL_QUEUEING(604, "排队中，请稍后查询结果"),
    SECKILL_FAIL(605, "秒杀失败"),
    SECKILL_RATE_LIMIT(606, "当前人数过多，请稍后重试"),

    // ===== 700 段：订单相关 =====
    ORDER_NOT_FOUND(700, "订单不存在"),
    ORDER_PAID(701, "订单已支付"),
    ORDER_CANCELED(702, "订单已取消"),

    // ===== 800 段：锁相关 =====
    LOCK_ACQUIRE_FAIL(800, "系统繁忙，请稍后重试");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
