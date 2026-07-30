package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static com.agenttrail.loop.core.support.ChatResponses.toolCallFragment;
import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopExecutorToolCallTest {

    @Test
    void executesRequestedToolThenReturnsFinalAnswer() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{\"text\":\"ping\"}")),
                List.of(text("done: pong"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        List<AgentStreamEvent> events = executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).containsExactly(
                new AgentStreamEvent.ToolStart("echo", "call-1", "{\"text\":\"ping\"}"),
                new AgentStreamEvent.ToolEnd("echo", "call-1", "pong"),
                new AgentStreamEvent.Text("done: pong"),
                new AgentStreamEvent.Complete("conv-1")
        );
        assertThat(echoTool.recordedArguments()).containsExactly("{\"text\":\"ping\"}");
        assertThat(chatModel.roundCount()).isEqualTo(2);
    }

    @Test
    void executesToolWhoseArgumentsArrivedSplitAcrossChunks() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(
                        toolCall("call-1", "echo", "{\"text\":"),
                        toolCallFragment("call-1", "\"pi"),
                        toolCallFragment("call-1", "ng\"}")
                ),
                List.of(text("done: pong"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        List<AgentStreamEvent> events = executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(echoTool.recordedArguments()).containsExactly("{\"text\":\"ping\"}");
        assertThat(events).contains(new AgentStreamEvent.ToolEnd("echo", "call-1", "pong"));
    }

    /**
     * 端到端验证不可见通道：模型给出的 userId 是伪造的，执行时必须被 Runtime 覆盖掉。
     * 这是数据权限能力包的安全前提——权限主体不能由模型决定。
     */
    @Test
    void overridesModelSuppliedSystemParametersBeforeExecutingTheTool() {
        RecordingToolCallback sqlTool = new RecordingToolCallback(
                "executeSql", "runs sql", RecordingToolCallback.schemaWith("sql", "userId"), "1 row");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "executeSql", "{\"sql\":\"select 1\",\"userId\":\"someone-else\"}")),
                List.of(text("done"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(sqlTool), 5);
        RunnableParams params = new RunnableParams("conv-1", "u-42", Map.of("userId", "u-42"));

        executor.stream("run a query", params).collectList().block(Duration.ofSeconds(5));

        assertThat(sqlTool.recordedArguments()).singleElement().asString()
                .contains("\"userId\":\"u-42\"")
                .doesNotContain("someone-else");
    }
}
