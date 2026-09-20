package com.xgh.seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单实体
 *
 * @author 向国豪
 */
@Data
@TableName("t_seckill_order")
public class SeckillOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 订单号（雪花算法生成，全局唯一） */
    private String orderNo;

    private Long userId;

    private Long activityId;

    private Long goodsId;

    private BigDecimal seckillPrice;

    /**
     * 订单状态
     * 0-待支付 1-已支付 2-已取消 3-已退款
     */
    private Integer status;

    private LocalDateTime payTime;

    /** 支付超时时间——延迟消息到点后检查该时间决定是否回滚 */
    private LocalDateTime expireTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
