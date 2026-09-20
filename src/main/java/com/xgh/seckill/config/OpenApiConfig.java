package com.xgh.seckill.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI / Knife4j 文档配置
 *
 * 【解决什么问题】
 * 不配置时 doc.html 只有干巴巴的接口列表，没有项目说明、作者、版本，
 * 也没有全局的 Authorization 按钮——每个接口都要手动粘贴 Bearer Token。
 *
 * 配置后：
 * 1. 文档页显示项目标题 / 描述 / 作者 / 版本
 * 2. 右上角「Authorize」按钮，粘贴一次 JWT，全部接口自动带上
 *
 * @author 向国豪
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "JWT认证";

    @Bean
    public OpenAPI seckillOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("分布式高并发秒杀系统 API")
                        .description("""
                                ### 项目简介
                                针对「瞬时流量与系统承载能力相差两个数量级」的矛盾，设计
                                **幂等 Token → Sentinel 限流 → Redis Lua 原子扣减 → 数据库条件更新兜底**
                                的四层防超卖架构，MQ 异步下单削峰，一人一单三重保证。

                                ### 调试流程
                                1. 调 `POST /auth/login` 拿 JWT（测试账号 `zhangsan / 123456`）
                                2. 点右上角 **Authorize**，粘贴 Token（不用加 Bearer 前缀）
                                3. `POST /seckill/warmup?activityId=1` 预热库存
                                4. `GET /seckill/token?activityId=1` → `POST /seckill/do` → 轮询 `GET /seckill/result`
                                5. `GET /seckill/orders` 查看订单，`POST /seckill/pay?orderNo=xxx` 支付

                                ### 演示前端
                                访问 [http://localhost:8080/](http://localhost:8080/) 体验完整秒杀流程。
                                """)
                        .version("1.0.0")
                        .contact(new Contact().name("向国豪").email("xgh@example.com")))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("粘贴 /auth/login 返回的 token（无需 Bearer 前缀）")));
    }
}
