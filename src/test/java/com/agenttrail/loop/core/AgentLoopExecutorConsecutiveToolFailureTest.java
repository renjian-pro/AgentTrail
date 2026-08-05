package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code maxConsecutiveToolFailures}：ReAct+Skill 路线没有 DataAgent 那种 Gate+maxRetries 的图结构
 * 上限，{@code maxRounds} 只是"整轮最多转几圈"的粗粒度硬顶，不区分"这几圈是不是拿同一个工具原地
 * 打转"。这里验证 Runtime 真的会在同一个工具连续失败达到阈值时提前止损，而不是干等到撞上
 * {@code maxRounds} 才收尾——对照 {@link AgentLoopExecutorMaxRoundsTest}，那边验证的是"到顶了怎么
 * 强制收尾"，这里验证的是"根本不用到顶，提前发现在原地打转就先收尾"。
 */
class AgentLoopExecutorConsecutiveToolFailureTest {

    @Test
    void stopsAfterTheSameToolFailsTheConfiguredNumberOfTimesInARow() {
        RecordingToolCallback failingTool = new RecordingToolCallback(
                "execute_sql", "runs sql", "Error: SQL 安全校验未通过：禁止的关键字");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "execute_sql", "{}")),
                List.of(toolCall("call-2", "execute_sql", "{}"))
        );
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(failingTool), 20)
                .maxConsecutiveToolFailures(2)
                .build();

        List<AgentStreamEvent> events = executor.stream("跑一个会失败的查询", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 只应该看到两轮真实的模型调用——阈值一到就地收尾，不会为了凑够 maxRounds=20 继续瞎转
        assertThat(chatModel.roundCount()).as("阈值命中后不应该再发起下一轮模型调用").isEqualTo(2);
        AgentStreamEvent.Text finalText = events.stream()
                .filter(AgentStreamEvent.Text.class::isInstance)
                .map(AgentStreamEvent.Text.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(finalText.content())
                .contains("execute_sql").contains("连续").contains("2").contains("SQL 安全校验未通过");
        assertThat(events).endsWith(new AgentStreamEvent.Complete("conv-1", null));
    }

    @Test
    void doesNotTripWhenTheToolRecoversBeforeReachingTheThreshold() {
        AtomicInteger callCount = new AtomicInteger();
        RecordingToolCallback flakyTool = new RecordingToolCallback("execute_sql", "runs sql", arguments -> {
            // 第一次失败，第二次成功——从没连续失败到 2 次，阈值不该被触发
            return callCount.incrementAndGet() == 1 ? "Error: 瞬时超时" : "查询成功，共 1 行。";
        });
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "execute_sql", "{}")),
                List.of(toolCall("call-2", "execute_sql", "{}")),
                List.of(text("已经查到结果了"))
        );
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(flakyTool), 20)
                .maxConsecutiveToolFailures(2)
                .build();

        List<AgentStreamEvent> events = executor.stream("先失败一次再成功", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chatModel.roundCount()).as("恢复之后应该正常走完三轮，不该被提前打断").isEqualTo(3);
        assertThat(events).endsWith(
                new AgentStreamEvent.Text("已经查到结果了"),
                new AgentStreamEvent.Complete("conv-1", null));
    }

    @Test
    void doesNotInterfereWhenTheMechanismIsNotConfigured() {
        RecordingToolCallback failingTool = new RecordingToolCallback(
                "execute_sql", "runs sql", "Error: 一直失败");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "execute_sql", "{}")),
                List.of(toolCall("call-2", "execute_sql", "{}")),
                List.of(text("超过轮次上限后的最佳答案"))
        );
        // 不调用 maxConsecutiveToolFailures(...)，默认 0——行为必须和这个机制完全不存在时一致，
        // 只受 maxRounds 约束。
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(failingTool), 2).build();

        List<AgentStreamEvent> events = executor.stream("一直失败也要转满 maxRounds", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chatModel.roundCount()).as("没配置这个机制时只受 maxRounds 约束").isEqualTo(3);
        assertThat(events).endsWith(
                new AgentStreamEvent.Text("超过轮次上限后的最佳答案"),
                new AgentStreamEvent.Complete("conv-1", null));
    }
}
