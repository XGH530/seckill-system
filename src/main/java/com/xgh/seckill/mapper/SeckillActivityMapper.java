package com.xgh.seckill.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xgh.seckill.entity.SeckillActivity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀活动 Mapper
 *
 * @author 向国豪
 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivity> {

    /**
     * 扣减库存（数据库层兜底，防止 Redis 失效导致超卖）
     *
     * 【核心考点】为什么用 seckill_stock >= #{num} 而不是先查再更新？
     * - "先查后改"存在竞态：两个线程都查到 stock=1，都判断通过，都执行 UPDATE → 超卖
     * - 把判断条件下沉到 UPDATE 语句里，由 InnoDB 行锁保证原子性：
     *   第一条 UPDATE 持有行锁改完 stock=0，第二条 UPDATE 再判断 stock>=1 失败，影响行数 0
     * - 返回值 affected rows = 0 即代表扣减失败（库存不足），业务层据此回滚
     *
     * @param activityId 活动ID
     * @param num        扣减数量
     * @return 影响行数，0 表示库存不足
     */
    @Update("UPDATE t_seckill_activity " +
            "SET seckill_stock = seckill_stock - #{num}, " +
            "    version = version + 1, " +
            "    update_time = NOW() " +
            "WHERE id = #{activityId} " +
            "  AND seckill_stock >= #{num}")
    int deductStock(@Param("activityId") Long activityId, @Param("num") int num);

    /**
     * 回滚库存（订单超时未支付时调用）
     *
     * @param activityId 活动ID
     * @param num        回滚数量
     * @return 影响行数
     */
    @Update("UPDATE t_seckill_activity " +
            "SET seckill_stock = seckill_stock + #{num}, " +
            "    version = version + 1, " +
            "    update_time = NOW() " +
            "WHERE id = #{activityId} " +
            "  AND seckill_stock + #{num} <= initial_stock")
    int rollbackStock(@Param("activityId") Long activityId, @Param("num") int num);
}
