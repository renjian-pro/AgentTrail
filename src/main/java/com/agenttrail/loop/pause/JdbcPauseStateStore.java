package com.agenttrail.loop.pause;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * {@link PauseStateStore} 的 JDBC 落地实现——整份 {@link PauseState} 序列化成一段 JSON
 * （见 {@link PauseStateJson}）存进 {@code agent_pause_state} 表的 {@code snapshot_json} 列，
 * 表结构见 {@code db/schema.sql}。
 *
 * <p>之所以整体序列化成一个 JSON 字段，而不是像 {@code TraceRecord}/{@code MemoryItem} 那样按列存：
 * {@code PauseState} 本身是一份复杂的嵌套快照（消息历史、挂起的工具调用、运行时参数……），
 * 不是天然扁平的记录，拆列存储既没有额外的查询收益，还要多维护一堆列。
 *
 * <p>{@code conversation_id} 是唯一键，{@code save} 用 {@code ON DUPLICATE KEY UPDATE} 覆盖，
 * 对应 {@link PauseStateStore#save} "同一个 conversationId 再次保存视为覆盖"的约定。
 */
public class JdbcPauseStateStore implements PauseStateStore {

    private static final String UPSERT_SQL = """
            INSERT INTO agent_pause_state (conversation_id, reason, paused_at, snapshot_json)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                reason        = VALUES(reason),
                paused_at     = VALUES(paused_at),
                snapshot_json = VALUES(snapshot_json)
            """;

    private static final String SELECT_SQL = """
            SELECT snapshot_json FROM agent_pause_state WHERE conversation_id = ?
            """;

    private static final String DELETE_SQL = """
            DELETE FROM agent_pause_state WHERE conversation_id = ?
            """;

    private final JdbcClient jdbcClient;

    public JdbcPauseStateStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public void save(PauseState state) {
        jdbcClient.sql(UPSERT_SQL)
                .param(state.conversationId())
                .param(state.reason().name())
                .param(state.pausedAtMillis())
                .param(PauseStateJson.toJson(state))
                .update();
    }

    @Override
    public Optional<PauseState> find(String conversationId) {
        return jdbcClient.sql(SELECT_SQL)
                .param(conversationId)
                .query(String.class)
                .optional()
                .map(PauseStateJson::fromJson);
    }

    @Override
    public boolean delete(String conversationId) {
        int rows = jdbcClient.sql(DELETE_SQL)
                .param(conversationId)
                .update();
        return rows > 0;
    }
}
