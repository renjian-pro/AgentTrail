package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ChatResponses;
import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用轮结束后落进历史的那条助手消息，必须满足供应商客户端的两个硬要求：
 *
 * <ol>
 *   <li>assistant 消息的 {@code content} 不能是 null——{@code Assert.state(text != null, "text must not be null")}；
 *   <li>发给 {@code stream()} 的 {@code ChatOptions} 不能被换成通用类型——{@code createRequest}
 *       不能把客户端提供的具体 options 类型替换成通用实现。
 * </ol>
 */
class AgentLoopExecutorHistoryMessageTest {

    @Test
    void toolCallRoundHistoryMessageHasEmptyContentNotNull() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(ChatResponses.toolCall("call-1", "echo", "{\"text\":\"ping\"}")),
                List.of(ChatResponses.text("done"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // index 0 是无条件注入的当前日期系统消息；第 2 轮（index 1）发出去的历史里，
        // 第 3 条消息（index 2）才是上一轮落进去的 assistant 消息
        AssistantMessage historyMessage = (AssistantMessage) chatModel.messagesAtRound(1).get(2);
        assertThat(historyMessage.getText()).isNotNull().isEmpty();
    }

    @Test
    void toolCallRoundHistoryMessageCarriesReasoningContentInMetadata() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        AssistantMessage toolCallWithReasoning = AssistantMessage.builder()
                .toolCalls(List.of(ChatResponses.call("call-1", "echo", "{\"text\":\"ping\"}")))
                .properties(Map.of("reasoning_content", "先想想要不要调用 echo"))
                .build();
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(new ChatResponse(List.of(new Generation(toolCallWithReasoning)))),
                List.of(ChatResponses.text("done"))
        );
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(echoTool), 5)
                .thinkingMode(ThinkingMode.REASONING_CONTENT)
                .build();

        executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        AssistantMessage historyMessage = (AssistantMessage) chatModel.messagesAtRound(1).get(2);
        assertThat(historyMessage.getMetadata().get("reasoning_content")).isEqualTo("先想想要不要调用 echo");
    }

    /** 用生产供应商的 options 类型验证 LlmInvoker 不会用通用实现覆盖掉厂商具体类型。 */
    @Test
    void preservesProviderSpecificOptionsTypeInsteadOfReplacingWithGenericImplementation() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        OpenAiChatOptions providerDefaults = OpenAiChatOptions.builder().model("test-model").build();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(ChatResponses.text("done")))
                .withDefaultOptions(providerDefaults);
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        executor.stream("hello", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.optionsAtRound(0)).isInstanceOf(OpenAiChatOptions.class);
        assertThat(((ToolCallingChatOptions) chatModel.optionsAtRound(0)).getToolCallbacks())
                .containsExactly(echoTool);
    }
}
