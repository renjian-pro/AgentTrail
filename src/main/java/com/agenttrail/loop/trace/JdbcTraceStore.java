package com.agenttrail.loop.trace;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link TraceStore} 的 JDBC 落地实现——每一行对应一次模型调用，表结构见 {@code db/schema.sql}
 * 的 {@code agent_trace} 表。
 */
public class JdbcTraceStore implements TraceStore {

    private static final String INSERT_SQL = """
            INSERT INTO agent_trace
                (conversation_id, round, input_data, output_data, think,
                 prompt_tokens, completion_tokens, duration_millis, success, error_message, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /** 一个会话的完整 trace 永远按轮次正序读回，重建执行时间线。 */
    private static final String SELECT_BY_CONVERSATION_SQL = """
            SELECT conversation_id, round, input_data, output_data, think,
                   prompt_tokens, completion_tokens, duration_millis, success, error_message, recorded_at
            FROM agent_trace
            WHERE conversation_id = ?
            ORDER BY round ASC, id ASC
            """;

    private final JdbcClient jdbcClient;

    public JdbcTraceStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public void save(TraceRecord record) {
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
}
