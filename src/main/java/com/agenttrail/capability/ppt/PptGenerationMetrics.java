package com.agenttrail.capability.ppt;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * PPT 状态机的低基数指标门面。
 *
 * <p>stage/outcome/retryClass 都来自有限枚举，不把 taskId、conversationId、异常全文或用户内容
 * 放进标签；这样既能回答“哪一阶段慢/失败/重试多”，也不会因为每个任务创建一组时间序列拖垮
 * 指标系统。测试和没有配置 Micrometer 的旧装配传入 {@code null} 时自动退化为空操作。</p>
 */
public final class PptGenerationMetrics {

    private final MeterRegistry registry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();

    public PptGenerationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void stageSucceeded(PptState stage, long elapsedNanos) {
        stageOutcome(stage, "success", elapsedNanos);
    }

    public void stageFailed(PptState stage, long elapsedNanos, String retryClass) {
        stageOutcome(stage, "failure", elapsedNanos);
        increment("ppt.stage.retries", stage.name(), normalizeRetryClass(retryClass));
    }

    public void taskSucceeded() {
        increment("ppt.task.completed", "task", "success");
    }

    public void taskFailed() {
        increment("ppt.task.completed", "task", "failure");
    }

    public void taskCancelled() {
        increment("ppt.task.completed", "task", "cancelled");
    }

    private void stageOutcome(PptState stage, String outcome, long elapsedNanos) {
        if (registry == null) return;
        Timer.builder("ppt.stage.duration")
                .tag("stage", stage.name())
                .tag("outcome", outcome)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry)
                .record(elapsedNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        increment("ppt.stage.completed", stage.name(), outcome);
    }

    private void increment(String metric, String first, String second) {
        if (registry == null) return;
        String key = metric + '|' + first + '|' + second;
        counters.computeIfAbsent(key, ignored -> Counter.builder(metric)
                .tag("stage", first)
                .tag("outcome", second)
                .register(registry)).increment();
    }

    private static String normalizeRetryClass(String retryClass) {
        if (retryClass == null || retryClass.isBlank()) return "NONE";
        return switch (retryClass) {
            case "RETRIABLE", "RATE_LIMITED", "DEGRADED", "CANCELLED", "FATAL", "NONE" -> retryClass;
            default -> "OTHER";
        };
    }
}
