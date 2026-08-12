package com.agenttrail.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.List;

/**
 * Token 走 {@code Authorization} 请求头而不是 Cookie（见 {@code sa-token.is-read-cookie: false}），
 * 浏览器不会在跨站请求里自动带上它，传统 CSRF 不是这个鉴权模型下的主要风险；这里配 CORS 是为了
 * 防止任意源用 JS 发起带自定义头的请求并读取响应。生产部署必须把
 * {@code agenttrail.cors.allowed-origins} 覆盖成真实前端域名——默认值只覆盖本机开发。
 */
@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter(
            @Value("${agenttrail.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(allowedOrigins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/agent/**", configuration);
        source.registerCorsConfiguration("/api/**", configuration);
        return new CorsFilter(source);
    }

    @Bean
    public SecurityHeadersFilter securityHeadersFilter() {
        return new SecurityHeadersFilter();
    }
}
