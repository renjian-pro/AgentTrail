package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.JdbcPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseState;
import com.agenttrail.loop.pause.PauseStateStore;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.persistence.JdbcSessionStore;
import com.agenttrail.loop.tools.idempotency.JdbcIdempotencyStore;
import com.agenttrail.platform.tools.ResumeSafePoint;
import com.agenttrail.support.MySqlContainerTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * issue #12 的第三条验收标准：会话历史不能只活在某一个进程的内存里，重启或换实例之后必须能
 * 恢复。这条能力其实是 issue #5（{@link JdbcSessionStore}）已经打好的地基——本类不重新实现
 * 持久化，只端到端验证“新 Loop 实例接手旧会话/暂停快照”的场景。
 */
@Testcontainers
class AgentLoopExecutorRestartRecoveryIT extends MySqlContainerTestSupport {

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() {
        dataSource = createDataSourceAndSchema();
    }

    @BeforeEach
    void resetTable() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_session").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_pause_state").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_tool_idempotency").update();
    }

    @Test
    void aFreshInstanceRecoversPriorHistoryFromTheSharedStoreAfterARestart() {
        JdbcSessionStore sharedStore = new JdbcSessionStore(dataSource);
        String conversationId = "conv-restart-1";

        // “实例 A”：独立的 AgentLoopExecutor + AgentTaskManager，处理第一轮，落库后这个对象就没用了。
        ScriptedChatModel firstInstanceModel = new ScriptedChatModel(List.of(text("你好，我记住你了")));
        AgentLoopExecutor instanceA = AgentLoopExecutor.builder(firstInstanceModel, List.of(), 5)
                .persistenceHook(sharedStore)
                .build();
        instanceA.stream("你好", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // “重启/换实例”：全新对象，内存状态和 A 完全无关，只共享同一个 MySQL 表。
        ScriptedChatModel secondInstanceModel = new ScriptedChatModel(List.of(text("还记得，你好呀")));
        AgentLoopExecutor instanceB = AgentLoopExecutor.builder(secondInstanceModel, List.of(), 5)
                .persistenceHook(sharedStore)
                .build();
        instanceB.stream("还记得我吗", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // index 0 是当前日期系统消息（内容每天都变），跳过它只比对持久化历史本身。
        List<Message> withoutDateSection = secondInstanceModel.messagesAtRound(0);
        assertThat(withoutDateSection.subList(1, withoutDateSection.size())).extracting(Message::getText)
                .containsExactly("你好", "你好，我记住你了", "还记得我吗");
    }

    @Test
    void aConversationNeverSeenBeforeStartsWithEmptyHistoryRatherThanFailing() {
        JdbcSessionStore sharedStore = new JdbcSessionStore(dataSource);
        ScriptedChatModel chatModel = new ScriptedChatModel(List.of(text("你好")));
        AgentLoopExecutor executor = AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .persistenceHook(sharedStore)
                .build();

        executor.stream("你好", new RunnableParams("conv-never-seen", "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        List<Message> withoutDateSection = chatModel.messagesAtRound(0);
        assertThat(withoutDateSection.subList(1, withoutDateSection.size()))
                .extracting(Message::getText).containsExactly("你好");
    }

    @Test
    void aFreshInstanceResumesARealMySqlPauseSnapshotAndDeletesItOnlyAfterCompletion() {
        String conversationId = "conv-pause-restart-1";
        String highRiskTool = "chargeCard";
        JdbcPauseStateStore firstStoreInstance = new JdbcPauseStateStore(dataSource);
        RecordingToolCallback chargeTool = new RecordingToolCallback(highRiskTool, "charges a card", "charged");
        AgentLoopExecutor instanceA = AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(toolCall("call-1", highRiskTool, "{\"amount\":100}"))),
                        List.of(chargeTool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of(highRiskTool), firstStoreInstance))
                .modelName("qwen-plus")
                .build();

        List<AgentStreamEvent> pausedEvents = instanceA
                .stream("充值 100 元", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        assertThat(pausedEvents).anyMatch(AgentStreamEvent.Paused.class::isInstance);
        assertThat(chargeTool.recordedArguments()).isEmpty();
        assertThat(firstStoreInstance.find(conversationId)).get()
                .extracting(state -> state.safePoint()).isEqualTo(ResumeSafePoint.BEFORE_TOOL_EXECUTION);

        // 模拟进程重启：执行器、任务管理器和 JDBC Store 对象全部重建，只共享 MySQL 中的快照。
        JdbcPauseStateStore restartedStoreInstance = new JdbcPauseStateStore(dataSource);
        AgentLoopExecutor instanceB = AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(text("充值成功"))), List.of(chargeTool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of(highRiskTool), restartedStoreInstance))
                .modelName("qwen-plus")
                .build();

        List<AgentStreamEvent> resumedEvents = instanceB
                .resume(conversationId, ResumeInstruction.ApprovalDecision.approve())
                .collectList().block(Duration.ofSeconds(5));

        assertThat(resumedEvents).anyMatch(AgentStreamEvent.Complete.class::isInstance);
        assertThat(chargeTool.recordedArguments()).containsExactly("{\"amount\":100}");
        assertThat(restartedStoreInstance.find(conversationId))
                .as("完整恢复成功后才删除真实 MySQL 中的检查点")
                .isEmpty();
    }

    @Test
    void restartBetweenToolCompletionAndAfterCheckpointReplaysThePersistedResult() {
        String conversationId = "conv-crash-window";
        String highRiskTool = "chargeCard";
        JdbcPauseStateStore durablePauseStore = new JdbcPauseStateStore(dataSource);
        JdbcIdempotencyStore durableIdempotency = new JdbcIdempotencyStore(dataSource);
        RecordingToolCallback tool = new RecordingToolCallback(highRiskTool, "charges a card", "charged");

        AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(toolCall("call-crash", highRiskTool, "{\"amount\":100}"))),
                        List.of(tool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of(highRiskTool), durablePauseStore))
                .modelName("qwen-plus")
                .build()
                .stream("充值 100 元", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        PauseStateStore crashBeforeAfterSave = new PauseStateStore() {
            @Override
            public void save(PauseState state) {
                if (state.safePoint() == ResumeSafePoint.AFTER_TOOL_EXECUTION) {
                    throw new IllegalStateException("simulated process crash before AFTER save");
                }
                durablePauseStore.save(state);
            }

            @Override
            public Optional<PauseState> find(String id) {
                return durablePauseStore.find(id);
            }

            @Override
            public boolean delete(String id) {
                return durablePauseStore.delete(id);
            }
        };
        AgentLoopExecutor crashingInstance = AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(text("不会到达"))), List.of(tool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of(highRiskTool), crashBeforeAfterSave))
                .idempotencyStore(durableIdempotency)
                .modelName("qwen-plus")
                .build();

        assertThatThrownBy(() -> crashingInstance.resume(
                conversationId, ResumeInstruction.ApprovalDecision.approve()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("before AFTER save");
        assertThat(tool.recordedArguments()).containsExactly("{\"amount\":100}");
        assertThat(durablePauseStore.find(conversationId)).get()
                .extracting(PauseState::safePoint).isEqualTo(ResumeSafePoint.BEFORE_TOOL_EXECUTION);

        // 全新执行器只能看到仍为 BEFORE 的暂停快照；持久化幂等结果必须阻止它再执行一次工具。
        JdbcPauseStateStore restartedPauseStore = new JdbcPauseStateStore(dataSource);
        AgentLoopExecutor restartedInstance = AgentLoopExecutor.builder(
                        new ScriptedChatModel(List.of(text("充值成功"))), List.of(tool), 5)
                .pauseConfig(new PauseConfig(java.util.Set.of(highRiskTool), restartedPauseStore))
                .idempotencyStore(new JdbcIdempotencyStore(dataSource))
                .modelName("qwen-plus")
                .build();
        List<AgentStreamEvent> recovered = restartedInstance.resume(
                        conversationId, ResumeInstruction.ApprovalDecision.approve())
                .collectList().block(Duration.ofSeconds(5));

        assertThat(recovered).anyMatch(AgentStreamEvent.Complete.class::isInstance);
        assertThat(tool.recordedArguments())
                .as("工具已成功、AFTER 未落库的崩溃窗口也只能产生一次副作用")
                .containsExactly("{\"amount\":100}");
        assertThat(restartedPauseStore.find(conversationId)).isEmpty();
    }
}
