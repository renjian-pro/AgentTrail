package com.agenttrail.loop.security;

import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;

import java.time.Duration;

/**
 * 单会话工具调用速率限制：防的是 ReAct 死循环里 {@code READ_ONLY} 工具被反复调用烧 token/请求量
 * （SKILL.md 的重试预算只是给模型的指导，不是强制；这里补一道 Runtime 级别真正有强制力的止损线）。
 *
 * <p>命中 {@code HIGH_RISK} 工具在这道限速生效前应该已经走 {@code PauseConfig} 的人工审批流程——
 * 两个机制作用对象有重叠（都可能拦下同一个工具调用）但目的不同：审批防未授权的高危操作，
 * 限速防同一会话在允许范围内的工具被无限重试。
 *
 * <p>用 Redisson 自带的 {@link RRateLimiter}（生产验证过的滑动窗口实现），不自己手写限流算法。
 * {@code redisson == null} 表示这台环境没配 Redis（参照 {@code RedisConfig}/{@code
 * AgentLoopExecutorConfig.agentTaskManager} 已验证过的"可选机制，拿不到就永远放行"降级方式），
 * 不能让"没配 Redis"变成"限速功能报错甚至应用启动失败"。
 */
public class ToolRateLimiter {

    private final RedissonClient redisson;
    private final int maxCallsPerWindow;
    private final Duration window;

    public ToolRateLimiter(RedissonClient redisson, int maxCallsPerWindow, Duration window) {
        this.redisson = redisson;
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.window = window;
    }

    /** 本次工具调用是否放行；{@code redisson} 为 null 时永远放行（未启用限速）。 */
    public boolean allow(String conversationId) {
        if (redisson == null) {
            return true;
        }
        RRateLimiter limiter = redisson.getRateLimiter("tool-rate:" + conversationId);
        // trySetRate 只在这个 key 第一次出现时真正生效，后续调用是空操作——不会因为反复调用
        // 而把窗口重置，语义和只在会话第一次调用时设置一次没有区别
        limiter.trySetRate(RateType.OVERALL, maxCallsPerWindow, window);
        return limiter.tryAcquire();
    }
}
