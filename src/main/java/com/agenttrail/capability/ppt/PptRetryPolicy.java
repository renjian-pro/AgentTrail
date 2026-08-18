package com.agenttrail.capability.ppt;

import java.time.Duration;

/**
 * 阶段级有界重试策略。attempt 由任务持久化并按当前 pipelineState 独立计数，
 * 退避计算是纯函数，便于恢复扫描和单元测试保持确定性。
 */
public record PptRetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public PptRetryPolicy {
        if (maxAttempts < 1 || baseDelay == null || baseDelay.isNegative() || baseDelay.isZero()
                || maxDelay == null || maxDelay.isNegative() || maxDelay.isZero()
                || maxDelay.compareTo(baseDelay) < 0) {
            throw new IllegalArgumentException("PPT retry policy must use positive bounded durations");
        }
    }

    public static PptRetryPolicy defaults() {
        return new PptRetryPolicy(3, Duration.ofSeconds(2), Duration.ofMinutes(2));
    }

    public boolean canRetry(int attempt) {
        return attempt < maxAttempts;
    }

    public long delayMillisForNextAttempt(int failedAttempt) {
        long multiplier = 1L << Math.min(Math.max(failedAttempt - 1, 0), 30);
        long candidate;
        try {
            candidate = Math.multiplyExact(baseDelay.toMillis(), multiplier);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Math.min(candidate, maxDelay.toMillis());
    }
}
