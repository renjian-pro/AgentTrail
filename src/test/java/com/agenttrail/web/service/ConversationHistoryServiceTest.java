package com.agenttrail.web.service;
import com.agenttrail.web.dto.ConversationSummaryResponse;
import com.agenttrail.web.dto.ConversationTurnResponse;
import com.agenttrail.web.dto.ConversationHistoryResponse;
import com.agenttrail.web.dto.ConversationPageResponse;

import com.agenttrail.loop.persistence.JdbcSessionStore;
import com.agenttrail.loop.persistence.TurnRecord;
import com.agenttrail.support.SharedMySql;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 会话回放分页跑真实 MySQL，验证 UI 查询和 Runtime token 预算历史是两条独立语义。 */
class ConversationHistoryServiceTest {

    private static DataSource dataSource;
    private JdbcSessionStore sessionStore;
    private ConversationHistoryService historyService;

    @BeforeAll
    static void createSchema() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                SharedMySql.jdbcUrl(), SharedMySql.username(), SharedMySql.password());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        new ResourceDatabasePopulator(new ClassPathResource("db/schema.sql")).execute(source);
        dataSource = source;
    }

    @BeforeEach
    void createServices() {
        sessionStore = new JdbcSessionStore(dataSource);
        historyService = new ConversationHistoryService(dataSource);
    }

    @Test
    void returnsOneConversationInChronologicalOrderAndDetectsNextPage() {
        String conversationId = "history-test-" + UUID.randomUUID();
        save(conversationId, "第一问", "第一答");
        save("other-history-test-" + UUID.randomUUID(), "不应出现", "不应出现");
        save(conversationId, "第二问", "第二答");

        ConversationHistoryResponse firstPage = historyService.findPage(conversationId, 0, 1);
        ConversationHistoryResponse secondPage = historyService.findPage(conversationId, 1, 1);

        assertThat(firstPage.hasMore()).isTrue();
        assertThat(firstPage.turns()).extracting(ConversationTurnResponse::question).containsExactly("第二问");
        assertThat(secondPage.hasMore()).isFalse();
        assertThat(secondPage.turns()).extracting(ConversationTurnResponse::question).containsExactly("第一问");
    }

    @Test
    void listsDistinctConversationsUsingTheirFirstQuestionAsStableTitle() {
        String firstConversation = "sessions-test-" + UUID.randomUUID();
        String secondConversation = "sessions-test-" + UUID.randomUUID();
        save(firstConversation, "旧标题", "旧答案");
        save(secondConversation, "另一会话", "答案");
        save(firstConversation, "最新标题", "新答案");

        ConversationPageResponse page = historyService.findConversations(0, 10);

        assertThat(page.sessions()).filteredOn(session -> session.conversationId().equals(firstConversation))
                .extracting(ConversationSummaryResponse::title)
                .containsExactly("旧标题");
        assertThat(page.sessions()).extracting(ConversationSummaryResponse::conversationId)
                .contains(secondConversation);
    }

    @Test
    void storesCapabilityPayloadsInTheSameConversationTimeline() {
        String conversationId = "capability-history-test-" + UUID.randomUUID();
        CapabilityConversationService capabilityService =
                new CapabilityConversationService(sessionStore, new ObjectMapper());

        capabilityService.recordSuccess(conversationId, "研究 Java 就业趋势", "研究结论",
                "research", java.util.Map.of("researchTopic", "Java 就业趋势", "report", "研究结论"), 1_200L);

        ConversationHistoryResponse page = historyService.findPage(conversationId, 0, 20);
        assertThat(page.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.question()).isEqualTo("研究 Java 就业趋势");
            assertThat(turn.answer()).isEqualTo("研究结论");
            assertThat(turn.timeline()).startsWith("[").contains(
                    "\"type\":\"StageOutput\"", "\"stage\":\"research\"",
                    "\"researchTopic\":\"Java 就业趋势\"");
        });
    }

    private void save(String conversationId, String question, String answer) {
        sessionStore.onTurnComplete(new TurnRecord(conversationId, "anonymous", question, answer,
                null, null, 10L, 20L));
    }
}
