package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.structured.JsonRepair;
import com.agenttrail.loop.task.AgentTaskManager;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #18：声明了 {@link OutputType} 时格式指令要注入到发给模型的 {@code UserMessage} 里，
 * 且 {@link AgentLoopExecutor#call} 要对最终答案做 JSON 修复；没声明时两者都不生效。
 */
class AgentLoopExecutorStructuredOutputTest {

    record Plan(String title, int priority) {
    }

    @Test
    void injectsFormatInstructionIntoTheUserMessageWhenOutputTypeIsDeclared() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("{\"title\":\"a\",\"priority\":1}")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);
        RunnableParams params = new RunnableParams("conv-1", "user-1", Map.of(), OutputType.of(Plan.class));

        executor.stream("plan my day", params).collectList().block(Duration.ofSeconds(5));

        UserMessage sentMessage = (UserMessage) chatModel.messagesAtRound(0).get(0);
        assertThat(sentMessage.getText()).startsWith("plan my day").contains("title").contains("priority");
    }

    @Test
    void leavesTheUserMessageUntouchedWhenNoOutputTypeIsDeclared() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("done")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5);

        executor.stream("plain question", new RunnableParams("conv-1", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        UserMessage sentMessage = (UserMessage) chatModel.messagesAtRound(0).get(0);
        assertThat(sentMessage.getText()).isEqualTo("plain question");
    }

    @Test
    void repairsMalformedJsonInCallWhenOutputTypeIsDeclared() {
        // 尾部多了一个逗号——模型很常见的小错误
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("{\"title\":\"a\",\"priority\":1,}")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5,
                new AgentTaskManager(), null, ThinkingMode.DISABLED, null);
        RunnableParams params = new RunnableParams("conv-2", "user-1", Map.of(), OutputType.of(Plan.class));

        String result = executor.call("plan my day", params);

        assertThat(result).isEqualTo("{\"title\":\"a\",\"priority\":1}");
    }

    @Test
    void degradesToAWrappedJsonWhenTheAnswerIsNotRepairable() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("sorry, I cannot help with that")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5,
                new AgentTaskManager(), null, ThinkingMode.DISABLED, null);
        RunnableParams params = new RunnableParams("conv-3", "user-1", Map.of(), OutputType.of(Plan.class));

        String result = executor.call("plan my day", params);

        assertThat(result).contains("sorry, I cannot help with that");
        assertThat(JsonRepair.isValidJson(result)).isTrue();
    }

    @Test
    void doesNotRepairTheAnswerInCallWhenNoOutputTypeIsDeclared() {
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("not json at all,")));
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(), 5,
                new AgentTaskManager(), null, ThinkingMode.DISABLED, null);

        String result = executor.call("hi", new RunnableParams("conv-4", "user-1"));

        assertThat(result).isEqualTo("not json at all,");
    }
}
