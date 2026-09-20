package com.xgh.seckill.controller;

import com.xgh.seckill.common.exception.BizException;
import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.common.result.ResultCode;
import com.xgh.seckill.common.util.JwtUtil;
import com.xgh.seckill.dto.SeckillRequest;
import com.xgh.seckill.service.SeckillService;
import com.xgh.seckill.vo.SeckillResultVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀接口
 *
 * @author 向国豪
 */
@Slf4j
@RestController
@RequestMapping("/seckill")
@RequiredArgsConstructor
@Tag(name = "秒杀", description = "秒杀相关接口")
public class SeckillController {

    private final SeckillService seckillService;
    private final JwtUtil jwtUtil;

    @GetMapping("/token")
    @Operation(summary = "获取幂等Token",
            description = "秒杀前必须先调本接口获取 Token，防止重复提交")
    public Result<String> getToken(@RequestParam Long activityId, HttpServletRequest request) {
        Long userId = currentUserId(request);
        String token = seckillService.generateIdempotentToken(userId, activityId);
        return Result.success("获取成功", token);
    }

    @PostMapping("/do")
    @Operation(summary = "执行秒杀",
            description = "立即返回「排队中」，真实下单由消息队列异步完成，前端需轮询 /seckill/result")
    public Result<SeckillResultVO> doSeckill(@RequestBody @Valid SeckillRequest req,
                                             HttpServletRequest request) {
        Long userId = currentUserId(request);
        SeckillResultVO result = seckillService.doSeckill(
                userId, req.getActivityId(), req.getIdempotentToken());
        return Result.success(result.getMessage(), result);
    }

    @GetMapping("/result")
    @Operation(summary = "查询秒杀结果", description = "前端轮询本接口获取最终结果")
    public Result<SeckillResultVO> queryResult(@RequestParam Long activityId,
                                               HttpServletRequest request) {
        Long userId = currentUserId(request);
        return Result.success(seckillService.queryResult(userId, activityId));
    }

    @GetMapping("/orders")
    @Operation(summary = "查询我的订单", description = "返回当前用户最近的秒杀订单（演示页使用）")
    public Result<java.util.List<com.xgh.seckill.entity.SeckillOrder>> myOrders(
            HttpServletRequest request) {
        Long userId = currentUserId(request);
        return Result.success(seckillService.listUserOrders(userId));
    }

    @PostMapping("/pay")
    @Operation(summary = "支付订单（模拟）")
    public Result<Void> pay(@RequestParam String orderNo, HttpServletRequest request) {
        Long userId = currentUserId(request);
        seckillService.payOrder(userId, orderNo);
        return Result.success("支付成功", null);
    }

    @PostMapping("/warmup")
    @Operation(summary = "预热库存到 Redis", description = "压测前先调用，把库存加载进缓存")
    public Result<Void> warmUp(@RequestParam Long activityId) {
        seckillService.warmUpStock(activityId);
        return Result.success("预热成功", null);
    }

    /**
     * 从请求头解析当前用户 ID
     * 【说明】完整实现应放在拦截器里，这里为了代码可读性做了简化
     */
    private Long currentUserId(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        Long userId = jwtUtil.getUserId(authorization.substring(7));
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED);
        }
        return userId;
    }
}
