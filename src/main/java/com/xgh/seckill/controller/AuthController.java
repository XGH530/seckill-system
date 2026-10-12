package com.xgh.seckill.controller;

import com.xgh.seckill.common.exception.BizException;
import com.xgh.seckill.common.result.Result;
import com.xgh.seckill.common.result.ResultCode;
import com.xgh.seckill.common.util.JwtUtil;
import com.xgh.seckill.entity.User;
import com.xgh.seckill.mapper.UserMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 用户认证接口
 *
 * @author 向国豪
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
@Tag(name = "认证", description = "登录 / 登出")
public class AuthController {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, Object> redisTemplate;

    private static final String TOKEN_BLACKLIST_KEY = "seckill:token:blacklist:";

    @PostMapping("/login")
    @Operation(summary = "登录", description = "测试账号：zhangsan / 123456")
    public Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
        User user = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, request.getUsername()));

        if (user == null) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名或密码错误");
        }

        // 【注意】生产环境必须用 BCrypt 校验，这里为便于演示做了简化
        // 正确写法：if (!BCrypt.checkpw(rawPassword, user.getPassword())) { ... }
        // 本项目 demo 密码统一为 123456
        if (!"123456".equals(request.getPassword())) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名或密码错误");
        }

        // 把角色写进 Token:管理台接口依赖 role=ADMIN 做鉴权(见 AdminAuthInterceptor)
        String token = jwtUtil.generateToken(user.getId(), user.getUsername(), user.getRole());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        data.put("nickname", user.getNickname());
        data.put("role", user.getRole());

        log.info("用户登录成功 username={} userId={}", user.getUsername(), user.getId());
        return Result.success("登录成功", data);
    }

    /**
     * 注册（幂等：已存在则直接返回该用户，便于压测批量造用户）
     *
     * 【为什么提供这个接口】
     * 并发压测需要「大量不同用户」同时抢购，才能真实压出库存竞争。
     * 只有 zhangsan/lisi/wangwu 三个账号无法验证防超卖效果，
     * 因此提供一个幂等注册接口专供压测与演示使用。
     *
     * 【生产环境注意】
     * 真实系统的注册接口需要：验证码校验、密码加密存储、手机号唯一校验、
     * 频率限制等。这里为了压测便利做了极简处理，切勿照搬到生产。
     */
    @PostMapping("/register")
    @Operation(summary = "注册（压测用，幂等）",
            description = "用户已存在时直接返回，便于批量造压测账号")
    public Result<Map<String, Object>> register(@RequestBody LoginRequest request) {
        String username = request.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(ResultCode.PARAM_ERROR, "用户名不能为空");
        }

        User existing = userMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<User>()
                        .eq(User::getUsername, username));

        User user = existing;
        if (user == null) {
            user = new User();
            user.setUsername(username);
            // demo 统一密码，生产环境必须 BCrypt 加密
            user.setPassword("123456");
            user.setNickname(username);
            userMapper.insert(user);
            log.info("新增压测用户 username={} userId={}", username, user.getId());
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());

        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("userId", user.getId());
        data.put("username", user.getUsername());

        return Result.success("注册成功", data);
    }

    /**
     * 登出
     * 【考点】JWT 无状态，如何实现登出？
     * → 黑名单机制：把未过期的 Token 加入 Redis 黑名单，
     *   网关/拦截器每次校验时先查黑名单，命中则拒绝。
     *   黑名单 key 的 TTL 设为 Token 剩余有效期，过期自动清理，不会无限增长。
     */
    @PostMapping("/logout")
    @Operation(summary = "登出")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            // 黑名单 TTL 设为 2 小时（与 Token 有效期一致）
            redisTemplate.opsForValue().set(TOKEN_BLACKLIST_KEY + token, "1", 2, TimeUnit.HOURS);
            log.info("用户登出，Token 已加入黑名单");
        }
        return Result.success();
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }
}
