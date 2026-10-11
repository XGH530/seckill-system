package com.xgh.seckill.controller;

import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 运维与监控接口
 *
 * =====================================================================
 * 【为什么需要这个 Controller】
 * =====================================================================
 * 很多项目只有「业务接口」，没有「运维接口」。
 * 但真实系统里，如果不能让运维**看见**系统状态、**干预**异常数据，
 * 那这套系统是不敢上生产的。
 *
 * 这个类提供三类能力：
 *   1. 预热管理  —— 活动上线前把库存/布隆过滤器准备好
 *   2. 数据修复  —— DB 与 Redis 不一致时能一键重置
 *   3. 在线对账  —— 实时对比 DB 库存 / Redis 库存 / 有效订单数
 *
 * 【为什么不加权限校验？】
 * 真实生产环境这类接口必须挂管理员鉴权（如 @PreAuthorize("hasRole('ADMIN')")）。
 * 本项目为演示便利做了简化——
 * 生产环境需要补上管理员鉴权。
 *
 * @author 向国豪
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "运维监控", description = "库存预热、数据修复、在线对账（演示环境未加鉴权）")
public class AdminController {

    private final SeckillService seckillService;
    private final com.xgh.seckill.component.SeckillMetrics metrics;

    @GetMapping("/metrics")
    @Operation(summary = "业务指标",
            description = "返回请求量、成功率、延迟分位、失败原因分布。压测后调这个接口直接拿数据。")
    public Result<Map<String, Object>> metrics() {
        return Result.success(metrics.snapshot());
    }

    @PostMapping("/metrics/reset")
    @Operation(summary = "重置业务指标", description = "每次压测前调用，保证每轮数据独立")
    public Result<Void> resetMetrics() {
        metrics.reset();
        return Result.success("指标已重置", null);
    }

    @PostMapping("/warmup/all")
    @Operation(summary = "批量预热全部活动",
            description = "把所有活动的库存写入 Redis，并登记进布隆过滤器。活动上线前调用。")
    public Result<Map<String, Object>> warmUpAll() {
        int count = seckillService.warmUpAll();
        return Result.success("批量预热完成", Map.of("warmedCount", count));
    }

    @PostMapping("/activity/{id}/reset-stock")
    @Operation(summary = "重置活动库存",
            description = "同时重置 DB 库存、Redis 库存、用户占位。压测/演示前用来恢复现场。")
    public Result<Void> resetStock(
            @PathVariable Long id,
            @Parameter(description = "新的库存数量") @RequestParam Integer stock) {
        seckillService.resetActivityStock(id, stock);
        return Result.success("库存已重置为 " + stock, null);
    }

    @PostMapping("/activity/{id}/clear-cache")
    @Operation(summary = "清除活动缓存",
            description = "活动信息（价格/时间）变更后调用，强制下次读取回源数据库")
    public Result<Void> clearCache(@PathVariable Long id) {
        seckillService.clearActivityCache(id);
        return Result.success("缓存已清除", null);
    }

    @GetMapping("/stats")
    @Operation(summary = "运行时统计与对账",
            description = "对比每个活动的 DB 库存 / Redis 库存 / 有效订单数，标记数据是否一致")
    public Result<Map<String, Object>> stats() {
        return Result.success(seckillService.runtimeStats());
    }
}
