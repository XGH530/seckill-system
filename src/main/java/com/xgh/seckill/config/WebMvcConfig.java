package com.xgh.seckill.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置:注册管理员鉴权拦截器
 *
 * 拦截器挂在 /admin/** 上,但具体是否校验由控制器上的 @RequireAdmin 注解决定,
 * 因此演示页(静态页)使用的运维接口不受影响。
 *
 * @author 向国豪
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminAuthInterceptor adminAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminAuthInterceptor)
                .addPathPatterns("/admin/**");
    }
}