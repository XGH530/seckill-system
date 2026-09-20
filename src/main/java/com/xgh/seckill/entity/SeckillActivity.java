package com.xgh.seckill.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体
 *
 * @author 向国豪
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long goodsId;

    private BigDecimal seckillPrice;

    /** 剩余库存 */
    private Integer seckillStock;

    /** 初始库存 */
    private Integer initialStock;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    /**
     * 乐观锁版本号
     * 【考点】@Version 是 MyBatis-Plus 的乐观锁插件注解，
     * 更新时会自动追加 WHERE version = ? 并在 SET 中 version = version + 1。
     * 但注意：秒杀场景下乐观锁冲突率极高，成功率低，
     *       所以本项目采用「Redis 前置拦截 + DB 条件更新兜底」而非纯乐观锁。
     */
    @Version
    private Integer version;

    private Integer status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
