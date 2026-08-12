package com.agenttrail.loop.security;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * 按 {@code userId} 的 HTTP 请求级限速，防的是单个账号反复刷 chat/deepresearch/ppt/上传这类开销
 * 较大的端点——{@link ToolRateLimiter} 管的是"一次对话内工具被重复调用"，这里管的是"一个用户在
 * Runtime 之外直接刷 HTTP 接口"，两者是不同层面的止损线，作用对象不重叠。
 *
 * <p>复用同一套 Redisson {@link RRateLimiter} 模式：{@code redisson == null} 表示没配 Redis，
 * 永远放行（和 {@link ToolRateLimiter} 一致的降级方式，不让"没配 Redis"变成启动失败）。
 */
public class HttpRateLimiter {

    private final RedissonClient redisson;
    private final int maxCallsPerWindow;
    private final Duration window;

    public HttpRateLimiter(RedissonClient redisson, int maxCallsPerWindow, Duration window) {
        this.redisson = redisson;
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.window = window;
    }

    /** 这次请求是否放行；{@code redisson} 为 null 或 {@code userId} 为 null(未登录路径)时永远放行。 */
    public boolean allow(String userId) {
        if (redisson == null || userId == null) {
            return true;
        }
        RRateLimiter limiter = redisson.getRateLimiter("http-rate:" + userId);
        limiter.trySetRate(RateType.OVERALL, maxCallsPerWindow, window);
        return limiter.tryAcquire();
    }
}
