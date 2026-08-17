package com.agenttrail.capability.deepresearch;

import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** {@link ResearchTaskRecordStore} 的 JDBC 实现（issue #108 / R20）。表结构见 {@code db/schema.sql}。 */
public class JdbcResearchTaskRecordStore implements ResearchTaskRecordStore {

    private static final String INSERT = """
            INSERT INTO research_task (user_id, conversation_id, question, status, created_at, updated_at)
            VALUES (?, ?, ?, 'RUNNING', ?, ?)
            """;

    /**
     * {@code AND status = 'RUNNING'} 不是可省的优化：worker 的完成事件和用户点取消可能几乎同时
     * 到达，先落地的那个才是真实原因。没有这个条件的话，一个已经 SUCCESS 的任务会被随后到达的
     * 取消请求改写成 CANCELLED，用户手里明明有报告，状态却说他取消了。
     */
    private static final String MARK_TERMINAL = """
            UPDATE research_task SET status = ?, error_msg = ?, updated_at = ?
            WHERE id = ? AND status = 'RUNNING'
            """;

    private static final String SELECT_BY_ID = """
            SELECT id, user_id, conversation_id, question, status, error_msg, created_at, updated_at
            FROM research_task WHERE id = ?
            """;

    private static final String SELECT_RUNNING_FOR_USER = """
            SELECT id FROM research_task WHERE status = 'RUNNING' AND user_id = ? ORDER BY id
            """;

    private static final String MARK_RUNNING_AS_INTERRUPTED = """
            UPDATE research_task SET status = 'FAILED', error_msg = ?, updated_at = ?
            WHERE status = 'RUNNING'
            """;

    private final JdbcClient jdbcClient;

    public JdbcResearchTaskRecordStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public long create(String userId, String conversationId, String question) {
        long now = System.currentTimeMillis();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(INSERT)
                .param(userId).param(conversationId).param(question).param(now).param(now)
                .update(keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("research_task 插入后拿不到自增主键");
        }
        return key.longValue();
    }

    @Override
    public void markTerminal(long id, DeepResearchTaskStatus status, String errorMsg) {
        jdbcClient.sql(MARK_TERMINAL)
                .param(status.name()).param(errorMsg).param(System.currentTimeMillis()).param(id)
                .update();
    }

    @Override
    public Optional<ResearchTaskRecord> find(long id) {
        return jdbcClient.sql(SELECT_BY_ID).param(id)
                .query(JdbcResearchTaskRecordStore::mapRow)
                .optional();
    }

    @Override
    public List<Long> runningIdsFor(String userId) {
        if (userId == null) {
            return List.of();
        }
        return jdbcClient.sql(SELECT_RUNNING_FOR_USER).param(userId).query(Long.class).list();
    }

    @Override
    public int markRunningAsInterrupted() {
        return jdbcClient.sql(MARK_RUNNING_AS_INTERRUPTED)
                .param(ResearchTaskRecord.INTERRUPTED_BY_RESTART)
                .param(System.currentTimeMillis())
                .update();
    }

    private static ResearchTaskRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ResearchTaskRecord(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                rs.getString("question"),
                DeepResearchTaskStatus.valueOf(rs.getString("status")),
                rs.getString("error_msg"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
