package com.agenttrail.loop.trace;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑真实 MySQL（见 {@link SharedMySql}），不用 H2——和 {@code JdbcSessionStoreIT} 同一条规矩。
 */
class JdbcTraceStoreIT {

    private static DataSource dataSource;
    private JdbcTraceStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_trace").update();
        store = new JdbcTraceStore(dataSource);
    }

    @Test
    void findByConversationIdReturnsRecordsInRoundOrder() {
        store.save(record("conv-1", 2, true));
        store.save(record("conv-1", 1, true));

        List<TraceRecord> records = store.findByConversationId("conv-1");

        assertThat(records).extracting(TraceRecord::round).containsExactly(1, 2);
    }

    @Test
    void keepsConversationsIsolatedFromEachOther() {
        store.save(record("conv-1", 1, true));
        store.save(record("conv-2", 1, true));

        assertThat(store.findByConversationId("conv-1")).extracting(TraceRecord::conversationId)
                .containsExactly("conv-1");
    }

    @Test
    void returnsNothingForAConversationThatHasNoTraceYet() {
        assertThat(store.findByConversationId("never-seen")).isEmpty();
    }

    @Test
    void roundTripsAllFieldsIncludingNegativeTokenSentinelsAndNullableColumns() {
        TraceRecord original = new TraceRecord("conv-1", 1, "input", "output", "think",
                -1, -1, 120L, true, null, 1_700_000_000_000L);

        store.save(original);

        assertThat(store.findByConversationId("conv-1")).containsExactly(original);
    }

    @Test
    void persistsAFailedRoundWithNullOutputDataAndAnErrorMessage() {
        TraceRecord failed = new TraceRecord("conv-1", 1, "input", null, null,
                10, 5, 80L, false, "模型调用超时", 1_700_000_000_000L);

        store.save(failed);

        assertThat(store.findByConversationId("conv-1")).containsExactly(failed);
    }

    private static TraceRecord record(String conversationId, int round, boolean success) {
        return new TraceRecord(conversationId, round, "input-" + round, "output-" + round, null,
                10, 5, 100L, success, null, 1_700_000_000_000L);
    }
}
