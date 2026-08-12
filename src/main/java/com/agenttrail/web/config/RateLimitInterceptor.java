package com.agenttrail.web.config;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.loop.security.HttpRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;

/**
 * registered after {@link SaTokenConfig}'s login-check interceptor, so an unauthenticated request
 * to a protected path is already rejected with 401 before it reaches here — {@code isLogin()} being
 * false at this point only happens on paths that don't require login (e.g. {@code /api/auth/login},
 * CORS preflight), which this interceptor doesn't rate-limit.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    private final HttpRateLimiter rateLimiter;

    public RateLimitInterceptor(HttpRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!StpUtil.isLogin()) {
            return true;
        }
        if (rateLimiter.allow(StpUtil.getLoginIdAsString())) {
            return true;
        }
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}");
        return false;
    }
}
