package com.xgh.seckill.controller;

import com.xgh.seckill.annotation.RequireAdmin;
import com.xgh.seckill.config.AdminAuthInterceptor;
import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.service.AdminManageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后台管理控制台接口
 *
 * 与 {@link AdminController} 的分工:
 *   - AdminController       : 运维动作(预热、重置库存、清缓存、指标、对账)
 *   - AdminManageController : 管理台页面所需的查询与基础维护(概览、活动、商品、订单、用户、健康检查)
 *
 * 【鉴权说明】演示环境未接入鉴权,生产环境需补管理员鉴权(详见 AdminController 类注释)。
 *
 * @author 向国豪
 */
@Slf4j
@RestController
@RequireAdmin
@RequestMapping("/admin")
@RequiredArgsConstructor
@Tag(name = "后台管理", description = "管理控制台:概览 / 活动 / 商品 / 订单 / 用户 / 健康检查")
public class AdminManageController {

    private final AdminManageService adminManageService;

    @GetMapping("/me")
    @Operation(summary = "当前管理员信息", description = "由 AdminAuthInterceptor 解析 Token 后透传,前端用它确认登录态")
    public Result<Map<String, Object>> me(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", request.getAttribute(AdminAuthInterceptor.ATTR_ADMIN_ID));
        data.put("username", request.getAttribute(AdminAuthInterceptor.ATTR_ADMIN_NAME));
        data.put("role", AdminAuthInterceptor.ROLE_ADMIN);
        return Result.success(data);
    }

    @GetMapping("/overview")
    @Operation(summary = "控制台概览", description = "活动、商品、用户、订单总量,库存汇总(DB/Redis 一致性)与实时业务指标")
    public Result<Map<String, Object>> overview() {
        return Result.success(adminManageService.overview());
    }

    @GetMapping("/activities")
    @Operation(summary = "活动列表(含在线对账)", description = "每行同时给出 DB 库存 / Redis 库存 / 有效订单数,并标记是否一致")
    public Result<List<Map<String, Object>>> activities() {
        return Result.success(adminManageService.activities());
    }

    @PostMapping("/activity/{id}/status")
    @Operation(summary = "上架/下架活动", description = "0-未开始,1-进行中,2-已结束;变更后会清除活动缓存")
    public Result<Void> updateActivityStatus(@PathVariable Long id, @RequestParam Integer status) {
        adminManageService.updateActivityStatus(id, status);
        return Result.success("活动状态已更新", null);
    }

    @GetMapping("/orders")
    @Operation(summary = "订单分页查询")
    public Result<Map<String, Object>> orders(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @Parameter(description = "0-待支付 1-已支付 2-已取消 3-已退款") @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Long activityId) {
        return Result.success(adminManageService.orders(current, size, status, activityId));
    }

    @PostMapping("/orders/{orderNo}/cancel")
    @Operation(summary = "人工关单", description = "取消订单并回补 DB/Redis 库存、释放一人一单占位(幂等,重复调用不会重复回补)")
    public Result<Boolean> cancelOrder(@PathVariable String orderNo) {
        boolean changed = adminManageService.cancelOrder(orderNo);
        return Result.success(changed ? "关单成功,库存已回补" : "订单已被其它链路处理,无需重复关单", changed);
    }

    @GetMapping("/users")
    @Operation(summary = "用户分页查询")
    public Result<Map<String, Object>> users(
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String keyword) {
        return Result.success(adminManageService.users(current, size, keyword));
    }

    @GetMapping("/goods")
    @Operation(summary = "商品列表")
    public Result<List<Map<String, Object>>> goods() {
        return Result.success(adminManageService.goods());
    }

    @PostMapping("/goods")
    @Operation(summary = "新增商品")
    public Result<Long> createGoods(@RequestBody Map<String, Object> body) {
        return Result.success("商品已创建", adminManageService.createGoods(body));
    }

    @PutMapping("/goods/{id}")
    @Operation(summary = "修改商品")
    public Result<Void> updateGoods(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        adminManageService.updateGoods(id, body);
        return Result.success("商品已更新", null);
    }

    @DeleteMapping("/goods/{id}")
    @Operation(summary = "删除商品", description = "逻辑删除;仍有进行中活动时拒绝删除")
    public Result<Void> deleteGoods(@PathVariable Long id) {
        adminManageService.deleteGoods(id);
        return Result.success("商品已删除", null);
    }

    @GetMapping("/health")
    @Operation(summary = "运行时健康检查", description = "数据库 / Redis 连通性与耗时、JVM 内存与运行时长")
    public Result<Map<String, Object>> health() {
        return Result.success(adminManageService.health());
    }
}