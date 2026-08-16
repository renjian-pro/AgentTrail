package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.trace.InMemoryTraceStore;
import com.agenttrail.loop.trace.TraceRecord;
import com.agenttrail.loop.trace.TraceStore;
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

    /**
     * 审计落库整个坏掉时，这一轮仍然必须结束。
     *
     * <p>回归的是 2026-08-16 那次全线挂死：{@code agent_trace} 少了 {@code prompt_stamps} 一列，
     * 成功路径上的 trace 落库抛 {@code BadSqlGrammarException}，转到 {@code failRun}，而
     * {@code failRun} 头一件事又是落 trace、又抛同一个异常——于是事件流既不出 Error 也不 Complete。
     * 前端和跑批都挂着等一个不会来的结束信号，会话的单飞锁一直被占着再也发不出下一轮。
     * 一个审计表的建表遗漏，放大成了整条链路不可用，所以这里钉的不是"审计要能写成功"，
     * 而是"审计写不成功也不许把这一轮吞掉"。
     */
    @Test
    void stillTerminatesTheRunWhenEveryTraceWriteBlowsUp() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentTaskManager taskManager = new AgentTaskManager();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .traceStore(new ExplodingTraceStore())
                .taskManager(taskManager)
                .build();

        List<AgentStreamEvent> events = executor.stream("hi", new RunnableParams("conv-5", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(events).as("流必须自然结束，而不是永远挂着").isNotNull();
        assertThat(events).as("失败要以协议内的 Error 事件告诉调用方，而不是静默")
                .anyMatch(event -> event instanceof AgentStreamEvent.Error);
        assertThat(taskManager.hasRunningTask("conv-5"))
                .as("单飞锁必须释放，否则这个会话再也发不出下一轮").isFalse();
    }

    /** 落库全挂的 {@link TraceStore}——模拟表结构不匹配、审计库不可用这类"写不进去"的故障。 */
    private static final class ExplodingTraceStore implements TraceStore {
        @Override
        public void save(TraceRecord record) {
            throw new IllegalStateException("Unknown column 'prompt_stamps' in 'field list'");
        }

        @Override
        public List<TraceRecord> findByConversationId(String conversationId) {
            return List.of();
        }

        @Override
        public java.util.Optional<Integer> verifyChain(String conversationId) {
            return java.util.Optional.empty();
        }
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
