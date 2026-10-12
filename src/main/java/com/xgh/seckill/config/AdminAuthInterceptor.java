package com.xgh.seckill.config;

import com.xgh.seckill.annotation.RequireAdmin;
import com.xgh.seckill.common.exception.BizException;
import com.xgh.seckill.common.result.ResultCode;
import com.xgh.seckill.common.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员鉴权拦截器
 *
 * 【设计说明】
 * 前端隐藏入口不是权限控制,真正的边界必须在服务端。
 * 只用注解 @RequireAdmin 标注的控制器会被拦截:
 *   1. 必须带 Authorization: Bearer <token>
 *   2. token 必须有效且未过期
 *   3. token 里的 role 必须是 ADMIN
 * 任一不满足分别返回 401(未登录) / 403(非管理员),
 * 这样即使有人直接在浏览器里敲 /admin/orders 也拿不到数据。
 *
 * @author 向国豪
 */
@Component
@RequiredArgsConstructor
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    /** 管理员角色标识 */
    public static final String ROLE_ADMIN = "ADMIN";

    /** 请求属性名:供 Controller 读取当前管理员身份 */
    public static final String ATTR_ADMIN_ID = "adminUserId";
    public static final String ATTR_ADMIN_NAME = "adminUsername";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 跨域预检直接放行
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        // 只有标注了 @RequireAdmin 的控制器才校验,演示用的运维接口保持开放
        if (!handlerMethod.getBeanType().isAnnotationPresent(RequireAdmin.class)) {
            return true;
        }

        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new BizException(ResultCode.UNAUTHORIZED, "请先登录管理员账号");
        }

        Claims claims = jwtUtil.parseToken(authorization.substring(7).trim());
        if (claims == null) {
            throw new BizException(ResultCode.UNAUTHORIZED, "登录已过期，请重新登录");
        }

        String role = claims.get("role", String.class);
        if (!ROLE_ADMIN.equalsIgnoreCase(role)) {
            throw new BizException(ResultCode.FORBIDDEN, "该账号不是管理员，无权访问管理接口");
        }

        // 把身份透传给 Controller,避免重复解析 token
        request.setAttribute(ATTR_ADMIN_ID, claims.get("userId"));
        request.setAttribute(ATTR_ADMIN_NAME, claims.get("username"));
        return true;
    }
}