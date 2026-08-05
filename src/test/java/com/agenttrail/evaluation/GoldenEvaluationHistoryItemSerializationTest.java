package com.agenttrail.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 回归测试：这个类原来用 {@code Instant startedAt}，而应用自己的
 * {@code AgentLoopExecutorConfig.objectMapper()} bean 是没注册 JSR-310 模块的 {@code new
 * ObjectMapper()}——序列化 {@code Instant} 字段会直接抛异常，把
 * {@code /agent/v1/evaluation/history} 端点炸成 500（已用这个测试实际复现过）。改成跟
 * {@code TraceRecord.recordedAtMillis} 一致的 {@code long startedAtMillis} 后钉死这个行为。
 */
class GoldenEvaluationHistoryItemSerializationTest {

    @Test
    void serializesCleanlyWithThePlainObjectMapperTheAppActuallyUses() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); // 和 AgentLoopExecutorConfig.objectMapper() 完全一致
        long startedAtMillis = System.currentTimeMillis();
        GoldenEvaluationHistoryItem item = new GoldenEvaluationHistoryItem(
                "task-1", "SUCCESS", startedAtMillis, 10, 10, 1.0, Map.of("sql", 1.0));

        assertThatCode(() -> mapper.writeValueAsString(item)).doesNotThrowAnyException();
        assertThat(mapper.writeValueAsString(item)).contains("\"startedAtMillis\":" + startedAtMillis);
    }
}
