package com.agenttrail.loop.pause;

import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 跑真实 MySQL（见 {@link SharedMySql}），不用 H2——和 {@code JdbcSessionStoreIT} 同一条规矩。 */
class JdbcPauseStateStoreIT {

    record Plan(String title, int priority) {
    }

    private static DataSource dataSource;
    private JdbcPauseStateStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_pause_state").update();
        store = new JdbcPauseStateStore(dataSource);
    }

    @Test
    void returnsEmptyForAConversationThatHasNoPauseStateYet() {
        assertThat(store.find("never-seen")).isEmpty();
    }

    @Test
    void roundTripsAMinimalPauseState() {
        PauseState original = new PauseState(
                "conv-1",
                List.of(new UserMessage("帮我查一下天气")),
                List.of(new PendingToolCall("call-1", "getWeather", "{\"city\":\"北京\"}")),
                PauseReason.HITL_APPROVAL,
                SafePoint.BEFORE_TOOL_EXECUTION,
                "帮我查一下天气",
                new RunnableParams("conv-1", "user-1"),
                2,
                1_700_000_000_000L);

        store.save(original);

        assertThat(store.find("conv-1")).contains(original);
    }

    @Test
    void roundTripsToolCallsAndToolResponsesInsideTheMessageHistory() {
        Message assistantWithToolCall = AssistantMessage.builder()
                .content("好的，我来查一下")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "getWeather", "{}")))
                .build();
        Message toolResult = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "getWeather", "晴天 20 度")))
                .build();
        PauseState original = new PauseState(
                "conv-1",
                List.of(new UserMessage("天气怎么样"), assistantWithToolCall, toolResult),
                List.of(),
                PauseReason.USER_INTERRUPT,
                SafePoint.BEFORE_TOOL_EXECUTION,
                "天气怎么样",
                new RunnableParams("conv-1", "user-1"),
                1,
                1_700_000_000_000L);

        store.save(original);

        Optional<PauseState> restored = store.find("conv-1");
        assertThat(restored).isPresent();
        List<Message> messages = restored.get().messages();
        assertThat(messages.get(0).getText()).isEqualTo("天气怎么样");
        assertThat(messages.get(1).getText()).isEqualTo("好的，我来查一下");
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls())
                .extracting(AssistantMessage.ToolCall::name).containsExactly("getWeather");
        // ToolResponseMessage 的实际内容在 getResponses() 里，getText() 对它没有意义
        assertThat(((ToolResponseMessage) messages.get(2)).getResponses())
                .extracting(ToolResponseMessage.ToolResponse::responseData)
                .containsExactly("晴天 20 度");
    }

    @Test
    void roundTripsToolParamsAndOutputType() {
        RunnableParams params = new RunnableParams("conv-1", "user-1",
                Map.of("tenantId", "t-1"), OutputType.of(Plan.class));
        PauseState original = new PauseState("conv-1", List.of(new UserMessage("问题")), List.of(),
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION, "问题", params, 1, 1L);

        store.save(original);

        RunnableParams restoredParams = store.find("conv-1").orElseThrow().params();
        assertThat(restoredParams.toolParams()).containsEntry("tenantId", "t-1");
        assertThat(restoredParams.outputType().type()).isEqualTo(Plan.class);
    }

    @Test
    void savingTwiceForTheSameConversationOverwritesTheEarlierSnapshot() {
        PauseState first = new PauseState("conv-1", List.of(new UserMessage("第一次")), List.of(),
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION, "第一次",
                new RunnableParams("conv-1", "user-1"), 1, 1L);
        PauseState second = new PauseState("conv-1", List.of(new UserMessage("第二次")), List.of(),
                PauseReason.USER_INTERRUPT, SafePoint.BEFORE_TOOL_EXECUTION, "第二次",
                new RunnableParams("conv-1", "user-1"), 2, 2L);

        store.save(first);
        store.save(second);

        assertThat(store.find("conv-1")).contains(second);
    }

    @Test
    void deleteRemovesTheSnapshotAndReturnsTrueOnlyWhenSomethingWasThere() {
        store.save(new PauseState("conv-1", List.of(new UserMessage("问题")), List.of(),
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION, "问题",
                new RunnableParams("conv-1", "user-1"), 1, 1L));

        assertThat(store.delete("conv-1")).isTrue();
        assertThat(store.find("conv-1")).isEmpty();
        assertThat(store.delete("conv-1")).as("已经删过了，第二次没有可删的").isFalse();
    }
}
