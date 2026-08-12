package com.agenttrail.web.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Token 存在浏览器 localStorage 里（{@code sa-token.is-read-cookie: false}），真正的威胁面是 XSS
 * 而不是 CSRF——这几道响应头是对 XSS/点击劫持的纵深防御，不依赖前端各处自觉。CSP 的
 * {@code connect-src}/{@code img-src} 允许自身源和 blob（图表/文件预览走这两种），不放开
 * {@code unsafe-inline} script（Vue 编译产物不需要内联脚本）。
 */
public class SecurityHeadersFilter implements Filter {

    private static final String CSP = "default-src 'self'; "
            + "script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data: blob:; "
            + "connect-src 'self'; "
            + "frame-ancestors 'none'; "
            + "base-uri 'self'";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("Content-Security-Policy", CSP);
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            httpResponse.setHeader("X-Frame-Options", "DENY");
            httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        }
        chain.doFilter(request, response);
    }
}
