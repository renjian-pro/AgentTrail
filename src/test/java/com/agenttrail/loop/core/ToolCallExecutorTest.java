package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.model.AgentStreamEvent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

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

        executor.execute(List.of(call("call-1", "echo", "{\"text\":\"ping\"}")), sink, NO_INJECTION);

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
                List.of(call("call-1", "echo", "{\"text\":\"pin")), sink, NO_INJECTION);

        assertThat(tool.recordedArguments()).containsExactly("{}");
        assertThat(responses).singleElement().extracting(ToolResponse::responseData).isEqualTo("ok");
    }

    @Test
    void degradesNullAndBlankArgumentsToEmptyObject() {
        RecordingToolCallback tool = new RecordingToolCallback("noop", "does nothing", "ok");
        ToolCallExecutor executor = new ToolCallExecutor(List.of(tool));

        executor.execute(List.of(call("call-1", "noop", null), call("call-2", "noop", "   ")), sink, NO_INJECTION);

        assertThat(tool.recordedArguments()).containsExactly("{}", "{}");
    }

    /** 模型幻觉出一个不存在的工具时，把错误当成工具结果喂回去，不中断循环。 */
    @Test
    void reportsUnknownToolAsAToolResultRatherThanFailing() {
        ToolCallExecutor executor = new ToolCallExecutor(List.of());

        List<ToolResponse> responses = executor.execute(
                List.of(call("call-1", "does-not-exist", "{}")), sink, NO_INJECTION);

        assertThat(responses).singleElement().extracting(ToolResponse::responseData)
                .asString().contains("does-not-exist");
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
                call("call-2", "fast", "{}")), sink, NO_INJECTION);

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
        executor.execute(List.of(call("call-1", "first", "{}"), call("call-2", "second", "{}")), sink, NO_INJECTION);
        long elapsed = System.currentTimeMillis() - startedAt;

        assertThat(elapsed).isLessThan(250);
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
