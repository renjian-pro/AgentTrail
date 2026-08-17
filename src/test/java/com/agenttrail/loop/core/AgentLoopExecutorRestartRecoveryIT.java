package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.JdbcPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.pause.SafePoint;
import com.agenttrail.loop.persistence.JdbcSessionStore;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * issue #12 的第三条验收标准：会话历史不能只活在某一个进程的内存里，重启或换实例之后必须能
 * 恢复。这条能力其实是 issue #5（{@link JdbcSessionStore}）已经打好的地基——本类不重新实现
 * 持久化，只是端到端验证一次"新实例接手旧会话"这个具体场景：两个完全独立的
 * {@link AgentLoopExecutor}（各自的 {@link AgentTaskManager}、各自的内存状态，模拟两次
 * 独立的进程生命周期）共享同一个真实 MySQL 表，第二个"实例"必须能读到第一个"实例"落的历史。
 */
class AgentLoopExecutorRestartRecoveryIT {

    private static DataSource dataSource;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void resetTable() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_session").update();
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_pause_state").update();
    }

    @Test
    void aFreshInstanceRecoversPriorHistoryFromTheSharedStoreAfterARestart() {
        JdbcSessionStore sharedStore = new JdbcSessionStore(dataSource);
        String conversationId = "conv-restart-1";

        // "实例 A"：独立的 AgentLoopExecutor + AgentTaskManager，处理第一轮，落库后这个对象就没用了
        ScriptedChatModel firstInstanceModel = new ScriptedChatModel(List.of(text("你好，我记住你了")));
        AgentLoopExecutor instanceA = AgentLoopExecutor.builder(firstInstanceModel, List.of(), 5)
                .persistenceHook(sharedStore)
                .build();
        instanceA.stream("你好", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // "重启/换实例"：全新对象，内存状态和 A 完全无关，只共享同一个 MySQL 表
        ScriptedChatModel secondInstanceModel = new ScriptedChatModel(List.of(text("还记得，你好呀")));
        AgentLoopExecutor instanceB = AgentLoopExecutor.builder(secondInstanceModel, List.of(), 5)
                .persistenceHook(sharedStore)
                .build();
        instanceB.stream("还记得我吗", new RunnableParams(conversationId, "user-1"))
                .collectList().block(Duration.ofSeconds(5));

        // "实例 B" 发给模型的第一轮消息里必须带着"实例 A"落的那一轮——历史穿越了重启。
        // index 0 是无条件注入的当前日期系统消息（内容每天都变），跳过它只比对历史本身。
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

        // index 0 是无条件注入的当前日期系统消息，跳过它只比对历史本身
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
                .extracting(state -> state.safePoint()).isEqualTo(SafePoint.BEFORE_TOOL_EXECUTION);

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
}
