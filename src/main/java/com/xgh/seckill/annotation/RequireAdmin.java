package com.xgh.seckill.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记"需要管理员身份"的控制器
 *
 * 【设计说明】
 * 为什么用注解而不是把所有 /admin/** 都拦掉？
 * 因为本项目演示页(static/index.html)上还有一个「运维面板」,
 * 它调用的 /admin/metrics、/admin/warmup/all、/admin/stats 是刻意保持免鉴权的,
 * 方便现场演示与压测脚本直接调用。
 * 用注解可以精确地把"管理台接口"和"演示用运维接口"区分开:
 *   - 标注了 @RequireAdmin  → 必须携带 role=ADMIN 的 JWT,否则 401/403
 *   - 未标注               → 保持原有开放策略
 *
 * @author 向国豪
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAdmin {
}