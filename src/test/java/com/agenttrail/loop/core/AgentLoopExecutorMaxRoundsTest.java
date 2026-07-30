package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * maxRounds 是防止模型陷入"反复调工具但永远不给答案"的兜底闸门。
 *
 * <p>收尾方式不是直接抛异常或返回空——那样用户什么都拿不到；而是**换一个不带任何工具的请求**
 * 再问一次，逼模型基于已有信息直接给文字答案。工具从请求里物理拿掉，比在提示词里写
 * "禁止再调用工具"可靠得多：模型看不见工具，就不存在"不遵守指令"这回事。
 */
class AgentLoopExecutorMaxRoundsTest {

    @Test
    void forcesATextAnswerByRetryingWithoutToolsOnceMaxRoundsIsReached() {
        RecordingToolCallback loopingTool = new RecordingToolCallback("noop", "does nothing useful", "still thinking");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "noop", "{}")),
                List.of(toolCall("call-2", "noop", "{}")),
                List.of(text("best effort answer"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(loopingTool), 2);

        List<AgentStreamEvent> events = executor.stream("keep going forever", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).endsWith(
                new AgentStreamEvent.Text("best effort answer"),
                new AgentStreamEvent.Complete("conv-1", null));
        assertThat(chatModel.roundCount()).isEqualTo(3);
        assertThat(chatModel.toolNamesAtRound(0)).containsExactly("noop");
        assertThat(chatModel.toolNamesAtRound(1)).containsExactly("noop");
        assertThat(chatModel.toolNamesAtRound(2))
                .as("收尾那一轮必须不带任何工具，模型才没法继续调用")
                .isEmpty();
    }

    @Test
    void doesNotInterfereWhenTheModelFinishesWithinTheRoundBudget() {
        RecordingToolCallback tool = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("done"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of(tool), 5);

        List<AgentStreamEvent> events = executor.stream("echo once", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(events).endsWith(new AgentStreamEvent.Complete("conv-1", null));
        assertThat(chatModel.roundCount()).isEqualTo(2);
    }
}
