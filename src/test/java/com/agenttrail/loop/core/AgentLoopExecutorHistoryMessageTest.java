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
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工具调用轮结束后落进历史的那条助手消息，必须扛得住真实 DeepSeekChatModel 的两个硬要求
 * （见 engineering-pitfalls-and-highlights.md #5a⑤，静态证据来自反编译 spring-ai-deepseek
 * 2.0.0 的 {@code DeepSeekChatModel#createRequest}）：
 *
 * <ol>
 *   <li>assistant 消息的 {@code content} 不能是 null——{@code Assert.state(text != null, "text must not be null")}；
 *   <li>发给 {@code stream()} 的 {@code ChatOptions} 不能被换成通用类型——{@code createRequest}
 *       会把 {@code prompt.getOptions()} 硬转成 {@code DeepSeekChatOptions}。
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

        // 第 2 轮（index 1）发出去的历史里，第 2 条消息就是上一轮落进去的 assistant 消息
        AssistantMessage historyMessage = (AssistantMessage) chatModel.messagesAtRound(1).get(1);
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
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5,
                new AgentTaskManager(), null, ThinkingMode.REASONING_CONTENT);

        executor.stream("please echo ping", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        AssistantMessage historyMessage = (AssistantMessage) chatModel.messagesAtRound(1).get(1);
        assertThat(historyMessage.getMetadata().get("reasoning_content")).isEqualTo("先想想要不要调用 echo");
    }

    /** 用真实的 DeepSeekChatOptions（测试范围依赖）验证 LlmInvoker 不会用通用实现覆盖掉厂商具体类型。 */
    @Test
    void preservesProviderSpecificOptionsTypeInsteadOfReplacingWithGenericImplementation() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "Echoes the input back", "pong");
        DeepSeekChatOptions providerDefaults = DeepSeekChatOptions.builder().model("deepseek-chat").build();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(ChatResponses.text("done")))
                .withDefaultOptions(providerDefaults);
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        executor.stream("hello", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.optionsAtRound(0)).isInstanceOf(DeepSeekChatOptions.class);
        assertThat(((ToolCallingChatOptions) chatModel.optionsAtRound(0)).getToolCallbacks())
                .containsExactly(echoTool);
    }
}
