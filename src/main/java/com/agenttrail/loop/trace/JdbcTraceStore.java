package com.agenttrail.loop.trace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * {@link TraceStore} 的 JDBC 落地实现——每一行对应一次模型调用，表结构见 {@code db/schema.sql}
 * 的 {@code agent_trace} 表。
 */
public class JdbcTraceStore implements TraceStore {

    private static final String INSERT_SQL = """
            INSERT INTO agent_trace
                (conversation_id, round, input_data, output_data, think,
                 prompt_tokens, completion_tokens, duration_millis, success, error_message, recorded_at,
                 prev_hash, hash)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String LAST_HASH_SQL = """
            SELECT hash FROM agent_trace WHERE conversation_id = ? ORDER BY id DESC LIMIT 1 FOR UPDATE
            """;

    /** 一个会话的完整 trace 永远按轮次正序读回，重建执行时间线。 */
    private static final String SELECT_BY_CONVERSATION_SQL = """
            SELECT conversation_id, round, input_data, output_data, think,
                   prompt_tokens, completion_tokens, duration_millis, success, error_message, recorded_at
            FROM agent_trace
            WHERE conversation_id = ?
            ORDER BY round ASC, id ASC
            """;

    private static final String SELECT_CHAIN_SQL = """
            SELECT conversation_id, round, input_data, output_data, think,
                   prompt_tokens, completion_tokens, duration_millis, success, error_message, recorded_at,
                   prev_hash, hash
            FROM agent_trace
            WHERE conversation_id = ?
            ORDER BY id ASC
            """;

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;
    /** 同一 JVM 内的首条记录没有可供 FOR UPDATE 锁定的现存行，补一层按会话锁覆盖这个窗口。 */
    private final ConcurrentMap<String, Object> conversationLocks = new ConcurrentHashMap<>();

    public JdbcTraceStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    @Transactional
    public void save(TraceRecord record) {
        Object lock = conversationLocks.computeIfAbsent(record.conversationId(), ignored -> new Object());
        synchronized (lock) {
            transactionTemplate.executeWithoutResult(status -> saveInTransaction(record));
        }
    }

    private void saveInTransaction(TraceRecord record) {
        String prevHash = jdbcClient.sql(LAST_HASH_SQL)
                .param(record.conversationId())
                .query(String.class)
                .optional()
                .orElse(null);
        String hash = computeHash(record, prevHash);
        jdbcClient.sql(INSERT_SQL)
                .param(record.conversationId())
                .param(record.round())
                .param(record.inputData())
                .param(record.outputData())
                .param(record.think())
                .param(record.promptTokens())
                .param(record.completionTokens())
                .param(record.durationMillis())
                .param(record.success())
                .param(record.errorMessage())
                .param(record.recordedAtMillis())
                .param(prevHash)
                .param(hash)
                .update();
    }

    @Override
    public List<TraceRecord> findByConversationId(String conversationId) {
        return jdbcClient.sql(SELECT_BY_CONVERSATION_SQL)
                .param(conversationId)
                .query((rs, rowNum) -> new TraceRecord(
                        rs.getString("conversation_id"),
                        rs.getInt("round"),
                        rs.getString("input_data"),
                        rs.getString("output_data"),
                        rs.getString("think"),
                        rs.getLong("prompt_tokens"),
                        rs.getLong("completion_tokens"),
                        rs.getLong("duration_millis"),
                        rs.getBoolean("success"),
                        rs.getString("error_message"),
                        rs.getLong("recorded_at")))
                .list();
    }

    @Override
    public Optional<Integer> verifyChain(String conversationId) {
        List<TraceRecordWithHash> records = jdbcClient.sql(SELECT_CHAIN_SQL)
                .param(conversationId)
                .query((rs, rowNum) -> new TraceRecordWithHash(
                        new TraceRecord(
                                rs.getString("conversation_id"),
                                rs.getInt("round"),
                                rs.getString("input_data"),
                                rs.getString("output_data"),
                                rs.getString("think"),
                                rs.getLong("prompt_tokens"),
                                rs.getLong("completion_tokens"),
                                rs.getLong("duration_millis"),
                                rs.getBoolean("success"),
                                rs.getString("error_message"),
                                rs.getLong("recorded_at")),
                        rs.getString("prev_hash"),
                        rs.getString("hash")))
                .list();

        String expectedPrevHash = null;
        for (TraceRecordWithHash record : records) {
            if (record.hash() == null) {
                continue;
            }
            if (!Objects.equals(record.prevHash(), expectedPrevHash)
                    || !Objects.equals(record.hash(), computeHash(record.record(), record.prevHash()))) {
                return Optional.of(record.record().round());
            }
            expectedPrevHash = record.hash();
        }
        return Optional.empty();
    }

    private static String computeHash(TraceRecord record, String prevHash) {
        String payload = String.join("|",
                record.conversationId(), String.valueOf(record.round()),
                nullToEmpty(record.inputData()), nullToEmpty(record.outputData()),
                String.valueOf(record.promptTokens()), String.valueOf(record.completionTokens()),
                String.valueOf(record.success()), nullToEmpty(record.errorMessage()),
                String.valueOf(record.recordedAtMillis()), nullToEmpty(prevHash));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JDK 未提供 SHA-256", impossible);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record TraceRecordWithHash(TraceRecord record, String prevHash, String hash) {
    }
}
