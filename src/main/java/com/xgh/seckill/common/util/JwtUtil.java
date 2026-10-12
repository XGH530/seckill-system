package com.xgh.seckill.common.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类
 *
 * 【设计说明：JWT 的优缺点】
 * 优点：无状态（服务端不存 Session）、跨服务可用、适合分布式
 * 缺点：
 *   1. 无法主动失效（签发了就在有效期内一直有效）→ 解决：黑名单 + 短有效期
 *   2. Token 过长（比 SessionId 大很多）
 *   3. 默认只签名不加密，敏感信息不能放 payload
 *
 * @author 向国豪
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${seckill.jwt.secret:seckill-system-secret-key-for-jwt-signature-2026-xiangguohao}")
    private String secret;

    @Value("${seckill.jwt.expire-minutes:120}")
    private long expireMinutes;

    private SecretKey getKey() {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 生成 Token(默认普通用户角色,供注册/压测造号的旧调用方使用)
     */
    public String generateToken(Long userId, String username) {
        return generateToken(userId, username, "USER");
    }

    /**
     * 生成 Token(带角色)
     *
     * 【设计说明】把 role 放进 JWT,鉴权时无需查库,
     * 接口层用 role 做粗粒度权限判断(ADMIN / USER)。
     */
    public String generateToken(Long userId, String username, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("username", username);
        claims.put("role", role == null || role.isBlank() ? "USER" : role);

        Date now = new Date();
        Date expire = new Date(now.getTime() + expireMinutes * 60 * 1000);

        return Jwts.builder()
                .claims(claims)
                .subject(String.valueOf(userId))
                .issuedAt(now)
                .expiration(expire)
                .signWith(getKey())
                .compact();
    }

    /**
     * 解析 Token
     *
     * @return Claims，解析失败返回 null
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (Exception e) {
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 从 Token 中提取用户 ID
     */
    public Long getUserId(String token) {
        Claims claims = parseToken(token);
        if (claims == null) {
            return null;
        }
        return claims.get("userId", Long.class);
    }

    public boolean validate(String token) {
        return parseToken(token) != null;
    }
}
