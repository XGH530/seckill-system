package com.xgh.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgh.seckill.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀订单 Mapper
 *
 * @author 向国豪
 */
@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 查询用户在某活动下的有效订单（用于一人一单校验）
     * 【注意】status 为 2（已取消）的订单不算占用名额，允许重新下单
     */
    @Select("SELECT * FROM t_seckill_order " +
            "WHERE user_id = #{userId} AND activity_id = #{activityId} " +
            "  AND status != 2 LIMIT 1")
    SeckillOrder selectByUserAndActivity(@Param("userId") Long userId,
                                         @Param("activityId") Long activityId);

    /**
     * 更新订单为已支付（带状态条件，防止重复支付）
     *
     * @return 影响行数，0 表示订单状态已变更（重复支付 / 已取消）
     */
    @Update("UPDATE t_seckill_order " +
            "SET status = 1, pay_time = NOW(), update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int markPaid(@Param("orderNo") String orderNo);

    /**
     * 取消订单（带状态条件，幂等）
     *
     * @return 影响行数，0 表示订单已被处理过
     */
    @Update("UPDATE t_seckill_order " +
            "SET status = 2, update_time = NOW() " +
            "WHERE order_no = #{orderNo} AND status = 0")
    int markCanceled(@Param("orderNo") String orderNo);

    /**
     * 查询超时未支付的订单（定时任务兜底扫表）
     */
    @Select("SELECT * FROM t_seckill_order " +
            "WHERE status = 0 AND expire_time <= #{now} LIMIT #{limit}")
    List<SeckillOrder> selectTimeoutOrders(@Param("now") LocalDateTime now,
                                           @Param("limit") int limit);
}
