package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.TodoItem;
import com.agenttrail.loop.tools.TodoWriteTool;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.agenttrail.loop.core.support.ChatResponses.call;
import static org.assertj.core.api.Assertions.assertThat;

class ToolCallExecutorTest {

    /** 本类只测执行语义，系统级参数注入的行为由 {@link ToolParamInjectorTest} 单独覆盖。 */
    private static final ToolParamInjector NO_INJECTION = new ToolParamInjector(Map.of());

    private final Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    @Test
    void passesWellFormedArgumentsThroughUntouched() {
        RecordingToolCallback tool = new RecordingToolCallback("echo", "echoes", "ok");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(tool));

        executor.execute(List.of(call("call-1", "echo", "{\"text\":\"ping\"}")), sink::tryEmitNext, NO_INJECTION);

        assertThat(tool.recordedArguments()).containsExactly("{\"text\":\"ping\"}");
    }

    /**
     * 踩坑点 #2：分片拼完仍是半截 JSON（网络截断/模型输出被 maxTokens 砍掉）时，
     * 降级成空参数继续跑，而不是抛异常把整个循环炸掉——工具自己会因为缺参数报错，
     * 那个错误再喂回模型，模型有机会自我纠正；直接抛异常则整轮对话直接死掉。
     */
    @Test
    void degradesTruncatedArgumentsToEmptyObjectInsteadOfThrowing() {
        RecordingToolCallback tool = new RecordingToolCallback("echo", "echoes", "ok");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(tool));

        List<ToolResponse> responses = executor.execute(
                List.of(call("call-1", "echo", "{\"text\":\"pin")), sink::tryEmitNext, NO_INJECTION);

        assertThat(tool.recordedArguments()).containsExactly("{}");
        assertThat(responses).singleElement().extracting(ToolResponse::responseData).isEqualTo("ok");
    }

    @Test
    void degradesNullAndBlankArgumentsToEmptyObject() {
        RecordingToolCallback tool = new RecordingToolCallback("noop", "does nothing", "ok");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(tool));

        executor.execute(List.of(call("call-1", "noop", null), call("call-2", "noop", "   ")), sink::tryEmitNext, NO_INJECTION);

        assertThat(tool.recordedArguments()).containsExactly("{}", "{}");
    }

    /** 模型幻觉出一个不存在的工具时，把错误当成工具结果喂回去，不中断循环。 */
    @Test
    void reportsUnknownToolAsAToolResultRatherThanFailing() {
        ToolCallExecutor executor = new ToolCallExecutor(List.of());

        List<ToolResponse> responses = executor.execute(
                List.of(call("call-1", "does-not-exist", "{}")), sink::tryEmitNext, NO_INJECTION);

        assertThat(responses).singleElement().extracting(ToolResponse::responseData)
                .asString().contains("does-not-exist");
    }

    /** 外部 MCP 超时也是工具失败，必须作为结果喂回模型，不能炸掉整条 Agent 流。 */
    @Test
    void reportsToolExceptionsAsToolResultsRatherThanFailingTheAgentStream() {
        RecordingToolCallback timeout = new RecordingToolCallback("search", "times out", arguments -> {
            throw new IllegalStateException("upstream timed out after 30s");
        });
        ToolCallExecutor executor = new ToolCallExecutor(List.of(timeout));

        List<ToolResponse> responses = executor.execute(
                List.of(call("call-1", "search", "{}")), sink::tryEmitNext, NO_INJECTION);

        assertThat(responses).singleElement().extracting(ToolResponse::responseData)
                .asString().contains("error", "upstream timed out after 30s");
    }

    @Test
    void recordsToolDurationAndOutcomeCounters() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RecordingToolCallback success = new RecordingToolCallback("ok", "succeeds", "ok");
        RecordingToolCallback failure = new RecordingToolCallback("bad", "fails", arguments -> {
            throw new IllegalStateException("boom");
        });
        ToolCallExecutor executor = new ToolCallExecutor(List.of(success, failure), registry);

        executor.execute(List.of(call("call-1", "ok", "{}"), call("call-2", "bad", "{}")),
                sink::tryEmitNext, NO_INJECTION);

        assertThat(registry.get("agenttrail.tool.duration").timer("tool", "ok", "outcome", "success").count())
                .isEqualTo(1);
        assertThat(registry.get("agenttrail.tool.duration").timer("tool", "bad", "outcome", "failure").count())
                .isEqualTo(1);
        assertThat(registry.get("agenttrail.tool.calls").counter("tool", "ok", "outcome", "success").count())
                .isEqualTo(1);
        assertThat(registry.get("agenttrail.tool.calls").counter("tool", "bad", "outcome", "failure").count())
                .isEqualTo(1);
    }

    /**
     * 一轮里的多个工具并发执行，但结果必须按模型请求的原始顺序拼回——
     * OpenAI 形状的协议要求 tool 响应与 tool_call 一一对应，顺序错了模型侧会错位关联。
     * 这里让第一个工具故意跑得比第二个慢，如果实现是"谁先跑完谁先入列"就会翻车。
     */
    @Test
    void runsToolsConcurrentlyButFillsResponsesBackInRequestOrder() {
        RecordingToolCallback slowTool = new RecordingToolCallback("slow", "sleeps a bit", arguments -> {
            sleep(150);
            return "slow-result";
        });
        RecordingToolCallback fastTool = new RecordingToolCallback("fast", "returns at once", "fast-result");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(slowTool, fastTool));

        List<ToolResponse> responses = executor.execute(List.of(
                call("call-1", "slow", "{}"),
                call("call-2", "fast", "{}")), sink::tryEmitNext, NO_INJECTION);

        assertThat(responses).extracting(ToolResponse::id).containsExactly("call-1", "call-2");
        assertThat(responses).extracting(ToolResponse::responseData)
                .containsExactly("slow-result", "fast-result");
    }

    /** 并发是真并发：两个各睡 150ms 的工具，总耗时应显著小于串行的 300ms。 */
    @Test
    void executesIndependentToolsInParallelRatherThanOneAfterAnother() {
        RecordingToolCallback first = new RecordingToolCallback("first", "sleeps", arguments -> {
            sleep(150);
            return "a";
        });
        RecordingToolCallback second = new RecordingToolCallback("second", "sleeps", arguments -> {
            sleep(150);
            return "b";
        });
        ToolCallExecutor executor = new ToolCallExecutor(List.of(first, second));

        long startedAt = System.currentTimeMillis();
        executor.execute(List.of(call("call-1", "first", "{}"), call("call-2", "second", "{}")), sink::tryEmitNext, NO_INJECTION);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(elapsed).isLessThan(250);
    }

    /**
     * 核心约束（issue #8）：TodoProgress 事件的内容来自重新解析原始参数，不依赖工具的返回值——
     * 这里让工具故意返回一段和任务清单毫无关系的文本，事件内容仍必须精确反映提交的参数。
     */
    @Test
    void emitsTodoProgressFromReparsedArgumentsIndependentlyOfTheToolsReturnValue() {
        RecordingToolCallback todoWrite = new RecordingToolCallback(
                TodoWriteTool.TOOL_NAME, "todo tool", arguments -> "TOTALLY UNRELATED RETURN VALUE");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(todoWrite));
        String todosJson = "{\"todos\":[{\"content\":\"c1\",\"activeForm\":\"doing c1\",\"status\":\"in_progress\"}]}";

        executor.execute(List.of(call("call-1", TodoWriteTool.TOOL_NAME, todosJson)), sink::tryEmitNext, NO_INJECTION);
        sink.tryEmitComplete();

        List<AgentStreamEvent> events = sink.asFlux().collectList().block(Duration.ofSeconds(2));
        assertThat(events).filteredOn(AgentStreamEvent.TodoProgress.class::isInstance).singleElement()
                .isEqualTo(new AgentStreamEvent.TodoProgress(
                        List.of(new TodoItem("c1", "doing c1", TodoItem.Status.IN_PROGRESS))));
    }

    /**
     * 更严格的隔离证明：TodoWrite 的 inputSchema 里声明的字段（{@code todos}）本身
     * 也在系统参数注入的白名单里——如果快照取的是"已注入"的参数而不是模型的原始参数，
     * 这里就会看到被注入覆盖后的内容，而不是模型真正提交的那份。
     */
    @Test
    void emitsTodoProgressFromTheModelsRawArgumentsNotTheSystemParamInjectedOnes() {
        RecordingToolCallback todoWrite = new RecordingToolCallback(TodoWriteTool.TOOL_NAME, "todo tool", "ok");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(todoWrite));
        String modelSubmittedTodos =
                "{\"todos\":[{\"content\":\"模型提交的\",\"activeForm\":\"正在处理\",\"status\":\"pending\"}]}";
        // 系统参数注入白名单按 inputSchema 过滤，todos 恰好也在 TodoWrite 自己的 schema 里——
        // 如果快照取用了注入之后的参数，这里就会看到这份被覆盖的内容而不是模型原始提交的
        ToolParamInjector injectingTodos = new ToolParamInjector(
                Map.of("todos", List.of(Map.of("content", "被注入覆盖的", "activeForm", "?", "status", "completed"))));

        executor.execute(List.of(call("call-1", TodoWriteTool.TOOL_NAME, modelSubmittedTodos)), sink::tryEmitNext, injectingTodos);
        sink.tryEmitComplete();

        List<AgentStreamEvent> events = sink.asFlux().collectList().block(Duration.ofSeconds(2));
        assertThat(events).filteredOn(AgentStreamEvent.TodoProgress.class::isInstance).singleElement()
                .isEqualTo(new AgentStreamEvent.TodoProgress(
                        List.of(new TodoItem("模型提交的", "正在处理", TodoItem.Status.PENDING))));
    }

    @Test
    void doesNotEmitTodoProgressForOtherTools() {
        RecordingToolCallback echo = new RecordingToolCallback("echo", "echoes", "pong");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(echo));

        executor.execute(List.of(call("call-1", "echo", "{}")), sink::tryEmitNext, NO_INJECTION);
        sink.tryEmitComplete();

        List<AgentStreamEvent> events = sink.asFlux().collectList().block(Duration.ofSeconds(2));
        assertThat(events).noneMatch(AgentStreamEvent.TodoProgress.class::isInstance);
    }

    /**
     * issue #10：工具执行要用独立的调度器，不能共用 Reactor 默认的全局 {@code boundedElastic}——
     * 那个池子被应用里任何随手 subscribeOn 的阻塞代码共用，高并发下会互相排队阻塞（踩坑点 #62）。
     */
    @Test
    void executesToolsOnADedicatedSchedulerNamedAgentToolExecNotTheDefaultSharedOne() {
        List<String> observedThreadNames = new CopyOnWriteArrayList<>();
        RecordingToolCallback probe = new RecordingToolCallback("probe", "records its thread", arguments -> {
            observedThreadNames.add(Thread.currentThread().getName());
            return "ok";
        });
        ToolCallExecutor executor = new ToolCallExecutor(List.of(probe));

        executor.execute(List.of(call("call-1", "probe", "{}")), sink::tryEmitNext, NO_INJECTION);

        assertThat(observedThreadNames).singleElement().asString()
                .as("必须跑在专属的 agent-tool-exec 池上，既不是调用方线程也不是默认的 boundedElastic")
                .startsWith("agent-tool-exec")
                .isNotEqualTo(Thread.currentThread().getName());
    }

    /**
     * issue #10：MDC 是 ThreadLocal，工具执行跳到独立调度器的线程之后默认读不到——
     * 这里验证发起调用线程的 MDC 快照被正确还原到了执行工具的那个线程上。
     */
    @Test
    void toolExecutionCanReadTheCallingThreadsMdcAfterHoppingToTheDedicatedScheduler() {
        MDC.put("conversationId", "conv-42");
        try {
            List<String> observedValues = new CopyOnWriteArrayList<>();
            RecordingToolCallback probe = new RecordingToolCallback("probe", "reads MDC", arguments -> {
                observedValues.add(MDC.get("conversationId"));
                return "ok";
            });
            ToolCallExecutor executor = new ToolCallExecutor(List.of(probe));

            executor.execute(List.of(call("call-1", "probe", "{}")), sink::tryEmitNext, NO_INJECTION,
                    null, MDC.getCopyOfContextMap());

            assertThat(observedValues).containsExactly("conv-42");
        } finally {
            MDC.clear();
        }
    }

    /** 不传快照（null）时必须是完全的空操作，不能因为没有 MDC 就报错或者行为跑偏。 */
    @Test
    void toolExecutionWorksNormallyWhenNoMdcSnapshotIsProvided() {
        RecordingToolCallback echo = new RecordingToolCallback("echo", "echoes", "pong");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(echo));

        List<ToolResponse> responses = executor.execute(
                List.of(call("call-1", "echo", "{}")), sink::tryEmitNext, NO_INJECTION, null, null);

        assertThat(responses).singleElement().extracting(ToolResponse::responseData).isEqualTo("pong");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
