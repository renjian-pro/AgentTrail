package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.trace.InMemoryTraceStore;
import com.agenttrail.loop.trace.TraceRecord;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static com.agenttrail.loop.core.support.ChatResponses.usage;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #17：TraceAudit 只在配了 {@code TraceStore} 时才记录，且只记"模型调用"这个粒度——
 * 一轮一条，工具执行结果不单独记一条，而是随下一轮的输入原样出现。
 */
class AgentLoopExecutorTraceTest {

    @Test
    void recordsOneSuccessfulTraceForATextOnlyRound() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done"), usage(12, 4)));
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .traceStore(traceStore)
                .build();

        executor.stream("hi", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        List<TraceRecord> records = traceStore.findByConversationId("conv-1");
        assertThat(records).hasSize(1);
        TraceRecord onlyRecord = records.get(0);
        assertThat(onlyRecord.round()).isEqualTo(1);
        assertThat(onlyRecord.inputData()).contains("hi");
        assertThat(onlyRecord.outputData()).isEqualTo("done");
        assertThat(onlyRecord.success()).isTrue();
        assertThat(onlyRecord.errorMessage()).isNull();
        assertThat(onlyRecord.promptTokens()).isEqualTo(12);
        assertThat(onlyRecord.completionTokens()).isEqualTo(4);
        assertThat(onlyRecord.durationMillis()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void toolResultsSurfaceInTheNextRoundsInputInsteadOfTheirOwnRecord() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("final answer")));
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(echoTool), 5)
                .traceStore(traceStore)
                .build();

        executor.stream("echo something", new RunnableParams("conv-2", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        List<TraceRecord> records = traceStore.findByConversationId("conv-2");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).round()).isEqualTo(1);
        assertThat(records.get(0).outputData()).contains("echo").contains("调用工具");
        assertThat(records.get(1).round()).isEqualTo(2);
        assertThat(records.get(1).inputData()).as("工具结果应该原样出现在下一轮的输入里").contains("pong");
        assertThat(records.get(1).outputData()).isEqualTo("final answer");
    }

    @Test
    void recordsAFailedTraceWhenTheModelCallErrors() {
        ChatModelFailure failure = new ChatModelFailure();
        InMemoryTraceStore traceStore = new InMemoryTraceStore();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(failure, List.of(), 5)
                .traceStore(traceStore)
                .build();

        executor.stream("hi", new RunnableParams("conv-3", "user-1")).collectList().block(Duration.ofSeconds(5));

        List<TraceRecord> records = traceStore.findByConversationId("conv-3");
        assertThat(records).hasSize(1);
        TraceRecord onlyRecord = records.get(0);
        assertThat(onlyRecord.success()).isFalse();
        assertThat(onlyRecord.errorMessage()).contains("boom");
        assertThat(onlyRecord.outputData()).isNull();
    }

    @Test
    void doesNotRecordAnythingWhenNoTraceStoreIsConfigured() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        // 没有 TraceStore 可查——这里只断言配置了 null 之后循环照常跑完，不抛异常
        List<?> events = executor.stream("hi", new RunnableParams("conv-4", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).isNotEmpty();
    }

    /** 一个直接抛异常的 {@link org.springframework.ai.chat.model.ChatModel}，模拟模型调用失败。 */
    private static final class ChatModelFailure implements org.springframework.ai.chat.model.ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            throw new UnsupportedOperationException("只支持流式调用");
        }

        @Override
        public reactor.core.publisher.Flux<org.springframework.ai.chat.model.ChatResponse> stream(
                org.springframework.ai.chat.prompt.Prompt prompt) {
            return reactor.core.publisher.Flux.error(new RuntimeException("boom"));
        }
    }
}
