package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Set;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #15：同步调用是 {@link AgentLoopExecutor#stream} 的阻塞包装，不是另一套 loop——
 * 这里验证的是"包装对不对"，工具调用分片重组/单飞注册这些底层机制已经在
 * {@link AgentLoopExecutorToolCallTest}/{@link AgentTaskManagerTest} 里测过，不重复测。
 */
class AgentLoopExecutorSyncCallTest {

    @Test
    void returnsTheFinalTextForATextOnlyRound() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好，有什么可以帮你")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        String answer = executor.call("你好", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("你好，有什么可以帮你");
    }

    /**
     * 工具调用轮产生的正文是模型的中间思考，不是最终答案——只有最后一轮（无工具调用的收尾轮）
     * 的正文才应该出现在返回值里，之前轮次的文本必须被清空,不能拼接残留。
     */
    @Test
    void discardsTextFromToolCallRoundsAndOnlyReturnsTheFinalRoundsText() {
        RecordingToolCallback echoTool = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{\"text\":\"ping\"}")),
                List.of(text("done: pong"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(echoTool), 5);

        String answer = executor.call("please echo ping", new RunnableParams("conv-1", "user-1"));

        assertThat(answer).isEqualTo("done: pong");
        assertThat(echoTool.recordedArguments()).containsExactly("{\"text\":\"ping\"}");
    }

    @Test
    void throwsWhenTheModelCallFails() {
        AgentLoopExecutor executor = new AgentLoopExecutor(failingChatModel(), List.of(), 5);

        assertThatThrownBy(() -> executor.call("hi", new RunnableParams("conv-1", "user-1")))
                .isInstanceOf(AgentCallException.class)
                .extracting(ex -> ((AgentCallException) ex).code())
                .isEqualTo("LLM_CALL_FAILED");
    }

    @Test
    void throwsConcurrentExecutionWhenTheSameConversationIsAlreadyRunning() {
        AgentTaskManager sharedManager = new AgentTaskManager();
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .taskManager(sharedManager)
                .build();
        sharedManager.registerTask("conv-1", Sinks.many().unicast().onBackpressureBuffer());

        assertThatThrownBy(() -> executor.call("hi", new RunnableParams("conv-1", "user-1")))
                .isInstanceOf(AgentCallException.class)
                .extracting(ex -> ((AgentCallException) ex).code())
                .isEqualTo("CONCURRENT_EXECUTION");
    }

    /** 同步调用没有"之后再恢复"的自然落点，触发暂停时必须明确报错，不能悄悄返回一个不完整的答案。 */
    @Test
    void throwsWhenTheRoundTriggersAPauseInsteadOfCompleting() {
        InMemoryPauseStateStore store = new InMemoryPauseStateStore();
        RecordingToolCallback chargeTool = new RecordingToolCallback("chargeCard", "charges a card", "charged");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "chargeCard", "{\"amount\":100}")));
        PauseConfig pauseConfig = new PauseConfig(Set.of("chargeCard"), store);
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(chargeTool), 5)
                .pauseConfig(pauseConfig)
                .build();

        assertThatThrownBy(() -> executor.call("给我充值 100 元", new RunnableParams("conv-1", "user-1")))
                .isInstanceOf(AgentCallException.class)
                .extracting(ex -> ((AgentCallException) ex).code())
                .isEqualTo("PAUSED");
        // 被中止的调用绝不能被执行
        assertThat(chargeTool.recordedArguments()).isEmpty();
    }

    private static ChatModel failingChatModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("本类只测流式调用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                return Flux.error(new RuntimeException("模型不可用"));
            }
        };
    }
}
