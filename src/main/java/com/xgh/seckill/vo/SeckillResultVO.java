package com.xgh.seckill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀结果 VO
 *
 * @author 向国豪
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "秒杀结果")
public class SeckillResultVO implements Serializable {

    @Schema(description = "是否成功")
    private Boolean success;

    @Schema(description = "活动ID")
    private Long activityId;

    @Schema(description = "订单号")
    private String orderNo;

    @Schema(description = "提示信息")
    private String message;

    /**
     * 结果状态：0-排队中 1-成功 2-失败
     * 【设计说明】为什么秒杀要返回"排队中"？
     * 秒杀的正确答案就是"异步 + 轮询"：
     *  - 同步等待下单结果会让请求长时间占用 Tomcat 线程，吞吐量暴跌
     *  - 立即返回"排队中"，前端拿着 activityId 轮询结果接口
     *  - 后端用 MQ 异步完成真正的下单，削峰填谷
     */
    @Schema(description = "状态：0-排队中 1-成功 2-失败")
    private Integer status;

    public static SeckillResultVO queueing(Long activityId) {
        return new SeckillResultVO(false, activityId, null, "排队中，请稍后查询结果", 0);
    }

    public static SeckillResultVO success(Long activityId, String orderNo) {
        return new SeckillResultVO(true, activityId, orderNo, "秒杀成功", 1);
    }

    public static SeckillResultVO fail(Long activityId, String message) {
        return new SeckillResultVO(false, activityId, null, message, 2);
    }
}
