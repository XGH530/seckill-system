package com.xgh.seckill.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 秒杀请求 DTO
 *
 * @author 向国豪
 */
@Data
@Schema(description = "秒杀下单请求")
public class SeckillRequest {

    @NotNull(message = "活动ID不能为空")
    @Schema(description = "秒杀活动ID", example = "1")
    private Long activityId;

    @Schema(description = "幂等 Token（先调 /seckill/token 获取）")
    private String idempotentToken;
}
