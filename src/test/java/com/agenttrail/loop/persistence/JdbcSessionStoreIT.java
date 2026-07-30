package com.agenttrail.loop.persistence;

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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话持久化跑**真实 MySQL**，不用 H2。
 *
 * <p>项目明确禁用 H2 做集成测试：SQL 语法、AST 校验、后续的权限改写在 H2 上的行为和 MySQL
 * 不一致，用 H2 会让本该暴露的 bug 静静躺过测试。这里虽然只是简单的增删查，但同一条规矩
 * 从第一个集成测试就立住，后面数据分析能力包才不会破例。
 *
 * <p>连的是开发机上常驻的 MySQL（见 {@link SharedMySql}），不现拉现起容器——
 * 类之间的隔离靠每个用例前清表，不靠"各自一个新实例"。
 */
class JdbcSessionStoreIT {

    private static DataSource dataSource;
    private JdbcSessionStore store;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        // 直接跑生产用的建表脚本，保证测的就是真实表结构；IF NOT EXISTS 让重复执行是幂等的
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void resetTable() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_session").update();
        store = new JdbcSessionStore(dataSource);
    }

    @Test
    void returnsTheGeneratedTurnIdSoTheClientCanLinkAttachmentsToIt() {
        Long turnId = store.onTurnComplete(turn("conv-1", "第一问", "第一答"));

        assertThat(turnId).isNotNull().isPositive();
    }

    @Test
    void readsBackWhatItWroteAsAlternatingUserAndAssistantMessages() {
        store.onTurnComplete(turn("conv-1", "第一问", "第一答"));
        store.onTurnComplete(turn("conv-1", "第二问", "第二答"));

        List<Message> history = store.loadHistory("conv-1", 100_000);

        assertThat(history).extracting(Message::getText)
                .containsExactly("第一问", "第一答", "第二问", "第二答");
    }

    @Test
    void keepsConversationsIsolatedFromEachOther() {
        store.onTurnComplete(turn("conv-1", "属于会话一", "答一"));
        store.onTurnComplete(turn("conv-2", "属于会话二", "答二"));

        assertThat(store.loadHistory("conv-1", 100_000)).extracting(Message::getText)
                .containsExactly("属于会话一", "答一");
    }

    @Test
    void returnsNothingForAConversationThatHasNoHistoryYet() {
        assertThat(store.loadHistory("never-seen", 100_000)).isEmpty();
    }

    /** 预算吃紧时保留最近的轮次，最老的先被丢掉。 */
    @Test
    void dropsTheOldestTurnsWhenTheTokenBudgetIsTight() {
        store.onTurnComplete(turn("conv-1", "很久以前的问题".repeat(200), "很久以前的回答".repeat(200)));
        store.onTurnComplete(turn("conv-1", "刚刚的问题", "刚刚的回答"));

        List<Message> history = store.loadHistory("conv-1", 100);

        assertThat(history).isNotEmpty();
        assertThat(history).noneSatisfy(message ->
                assertThat(message.getText()).contains("很久以前"));
        assertThat(history.get(history.size() - 1).getText()).isEqualTo("刚刚的回答");
    }

    /** 答案为空（比如这一轮被中断了）时，历史里只保留提问，不能塞一条 null 内容的消息。 */
    @Test
    void skipsTheAnswerOfATurnThatNeverProducedOne() {
        store.onTurnComplete(new TurnRecord("conv-1", "u-1", "被中断的提问", null, null, null, null, null));

        assertThat(store.loadHistory("conv-1", 100_000)).extracting(Message::getText)
                .containsExactly("被中断的提问");
    }

    @Test
    void persistsThinkingSeparatelyFromTheAnswer() {
        store.onTurnComplete(new TurnRecord(
                "conv-1", "u-1", "问题", "答案", "推理过程", "[]", 120L, 3_400L));

        String think = JdbcClient.create(dataSource)
                .sql("SELECT think FROM agent_session WHERE conversation_id = ?")
                .param("conv-1")
                .query(String.class)
                .single();

        assertThat(think).isEqualTo("推理过程");
    }

    private static TurnRecord turn(String conversationId, String question, String answer) {
        return new TurnRecord(conversationId, "u-1", question, answer, null, null, 100L, 1_000L);
    }
}
