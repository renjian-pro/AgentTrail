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
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

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

    @Test
    void writesHashAndLinksTheSecondRecordToTheFirst() {
        TraceRecord first = record("conv-1", 1, true);
        TraceRecord second = record("conv-1", 2, true);

        store.save(first);
        store.save(second);

        List<Map<String, String>> hashes = JdbcClient.create(dataSource)
                .sql("SELECT prev_hash, hash FROM agent_trace WHERE conversation_id = ? ORDER BY id")
                .param("conv-1")
                .query((rs, rowNum) -> Map.of(
                        "prev", rs.getString("prev_hash") == null ? "" : rs.getString("prev_hash"),
                        "hash", rs.getString("hash")))
                .list();

        assertThat(hashes).hasSize(2);
        assertThat(hashes.get(0).get("prev")).isEmpty();
        assertThat(hashes.get(0).get("hash")).isEqualTo(expectedHash(first, null));
        assertThat(hashes.get(1).get("prev")).isEqualTo(hashes.get(0).get("hash"));
        assertThat(hashes.get(1).get("hash")).isEqualTo(expectedHash(second, hashes.get(0).get("hash")));
        assertThat(store.verifyChain("conv-1")).isEmpty();
    }

    @Test
    void detectsTamperingInTheFirstChangedRound() {
        store.save(record("conv-1", 1, true));
        store.save(record("conv-1", 2, true));
        store.save(record("conv-1", 3, true));

        JdbcClient.create(dataSource).sql("UPDATE agent_trace SET output_data = ? WHERE conversation_id = ? AND round = 2")
                .param("tampered")
                .param("conv-1")
                .update();

        assertThat(store.verifyChain("conv-1")).contains(2);
    }

    @Test
    void concurrentWritesKeepOneCompleteChain() throws Exception {
        int writes = 20;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int round = 1; round <= writes; round++) {
                int currentRound = round;
                pool.submit(() -> store.save(record("conv-concurrent", currentRound, true)));
            }
        } finally {
            pool.shutdown();
        }
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(store.findByConversationId("conv-concurrent")).hasSize(writes);
        assertThat(store.verifyChain("conv-concurrent")).isEmpty();
    }

    private static TraceRecord record(String conversationId, int round, boolean success) {
        return new TraceRecord(conversationId, round, "input-" + round, "output-" + round, null,
                10, 5, 100L, success, null, 1_700_000_000_000L);
    }

    private static String expectedHash(TraceRecord record, String prevHash) {
        String payload = String.join("|", record.conversationId(), String.valueOf(record.round()),
                empty(record.inputData()), empty(record.outputData()), String.valueOf(record.promptTokens()),
                String.valueOf(record.completionTokens()), String.valueOf(record.success()), empty(record.errorMessage()),
                String.valueOf(record.recordedAtMillis()), empty(prevHash));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
