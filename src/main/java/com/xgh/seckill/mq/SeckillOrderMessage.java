package com.xgh.seckill.mq;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 秒杀订单消息
 *
 * @author 向国豪
 */
@Data
public class SeckillOrderMessage implements Serializable {

    /** 消息唯一ID（消费端幂等键） */
    private String messageId;

    private Long userId;

    private Long activityId;

    private Long goodsId;

    private BigDecimal seckillPrice;

    /** 订单号（生产者提前生成，保证消费者幂等可追溯） */
    private String orderNo;

    public static SeckillOrderMessage of(String messageId, Long userId, Long activityId,
                                          Long goodsId, BigDecimal price, String orderNo) {
        SeckillOrderMessage msg = new SeckillOrderMessage();
        msg.setMessageId(messageId);
        msg.setUserId(userId);
        msg.setActivityId(activityId);
        msg.setGoodsId(goodsId);
        msg.setSeckillPrice(price);
        msg.setOrderNo(orderNo);
        return msg;
    }
}
