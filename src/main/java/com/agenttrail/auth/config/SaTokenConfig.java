package com.agenttrail.auth.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 默认拒绝的全局鉴权：先让 Sa-Token 执行注解，再用路由规则兜底保护所有接口。
 * 这样新增 Controller 时即使忘记注解也不会出现匿名入口；OPTIONS 预检请求不参与登录判定。
 *
 * <p>拦截范围必须限定在真正的接口前缀（{@code /agent/**}、{@code /api/**}），不能是 {@code /**}——
 * 前端 SPA 的 {@code index.html}/静态资源由 {@code ResourceHttpRequestHandler} 处理，不是
 * {@code @RestController} 方法，{@link com.agenttrail.auth.exception.SaTokenExceptionHandler}
 * 这个 {@code @RestControllerAdvice} 对它不生效；之前套 {@code /**} 时未登录访问 {@code /}
 * 会让拦截器抛出的 {@code NotLoginException} 没人接住，兜底成 Whitelabel 500 页——
 * 变成"要先登录才能看到登录页"的死循环。
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Bean
    public BCryptPasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public SaInterceptor saInterceptor() {
        return new SaInterceptor().isAnnotation(true).setAuth(obj -> {
            SaRouter.match("/agent/**", "/api/**")
                    .notMatch("/api/auth/login")
                    .check(r -> {
                        if (!"OPTIONS".equalsIgnoreCase(cn.dev33.satoken.context.SaHolder.getRequest().getMethod())) {
                            StpUtil.checkLogin();
                        }
                    });
        });
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(saInterceptor()).addPathPatterns("/agent/**", "/api/**");
    }
}
