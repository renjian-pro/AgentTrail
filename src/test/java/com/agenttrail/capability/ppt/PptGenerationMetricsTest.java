package com.agenttrail.capability.ppt;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 指标只使用有限阶段/结果标签，不把 taskId 或用户内容带进 MeterRegistry。 */
class PptGenerationMetricsTest {

    @Test
    void recordsStageLatencyAndNormalizesUnknownRetryLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PptGenerationMetrics metrics = new PptGenerationMetrics(registry);

        metrics.stageSucceeded(PptState.SCHEMA, 1_000_000);
        metrics.stageFailed(PptState.SCHEMA, 2_000_000, "provider-secret-error");

        assertThat(registry.get("ppt.stage.duration").tags("stage", "SCHEMA", "outcome", "success")
                .timer().count()).isEqualTo(1);
        assertThat(registry.get("ppt.stage.retries").tags("stage", "SCHEMA", "outcome", "OTHER")
                .counter().count()).isEqualTo(1);
    }
}
