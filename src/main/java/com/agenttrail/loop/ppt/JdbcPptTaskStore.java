package com.agenttrail.loop.ppt;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.util.Optional;

/**
 * {@link PptTaskStore} 的 JDBC 落地实现——表结构见 {@code db/schema.sql} 的
 * {@code ppt_generation_task} 表。
 *
 * <p>{@link #advance}/{@link #markFailed} 都是单行 UPDATE，天然原子——不需要额外加锁：
 * 这一票的编排是单线程顺序跑（{@link PptGenerationService#run(long)} 同步阻塞到 SUCCESS 或抛异常），
 * 不存在同一个任务被并发推进的场景。
 */
public class JdbcPptTaskStore implements PptTaskStore {

    private static final String INSERT_SQL = """
            INSERT INTO ppt_generation_task
                (conversation_id, status, error_msg, context_json, created_at, updated_at)
            VALUES (?, ?, NULL, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id, conversation_id, status, error_msg, context_json, created_at, updated_at
            FROM ppt_generation_task
            WHERE id = ?
            """;

    // 按 id 倒序取第一条即"最新"——id 自增，比 created_at 更可靠（同毫秒内可能有并列），
    // 和 InMemoryPptTaskStore#findLatestByConversationId 用同一个排序口径，两个实现行为一致。
    private static final String SELECT_LATEST_BY_CONVERSATION_SQL = """
            SELECT id, conversation_id, status, error_msg, context_json, created_at, updated_at
            FROM ppt_generation_task
            WHERE conversation_id = ?
            ORDER BY id DESC
            LIMIT 1
            """;

    private static final String ADVANCE_SQL = """
            UPDATE ppt_generation_task
            SET status = ?, error_msg = NULL, context_json = ?, updated_at = ?
            WHERE id = ?
            """;

    private static final String MARK_FAILED_SQL = """
            UPDATE ppt_generation_task
            SET status = ?, error_msg = ?, updated_at = ?
            WHERE id = ?
            """;

    private final JdbcClient jdbcClient;

    public JdbcPptTaskStore(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public long create(String conversationId, PptGenerationContext initialContext) {
        long now = System.currentTimeMillis();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(INSERT_SQL)
                .param(conversationId)
                .param(PptState.INIT.name())
                .param(PptContextJson.toJson(initialContext))
                .param(now)
                .param(now)
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<PptTask> findById(long id) {
        return jdbcClient.sql(SELECT_BY_ID_SQL)
                .param(id)
                .query(JdbcPptTaskStore::mapRow)
                .optional();
    }

    @Override
    public Optional<PptTask> findLatestByConversationId(String conversationId) {
        return jdbcClient.sql(SELECT_LATEST_BY_CONVERSATION_SQL)
                .param(conversationId)
                .query(JdbcPptTaskStore::mapRow)
                .optional();
    }

    @Override
    public void advance(long id, PptState newState, PptGenerationContext context) {
        jdbcClient.sql(ADVANCE_SQL)
                .param(newState.name())
                .param(PptContextJson.toJson(context))
                .param(System.currentTimeMillis())
                .param(id)
                .update();
    }

    @Override
    public void markFailed(long id, PptState failedState, String errorMsg) {
        jdbcClient.sql(MARK_FAILED_SQL)
                .param(failedState.name())
                .param(errorMsg)
                .param(System.currentTimeMillis())
                .param(id)
                .update();
    }

    private static PptTask mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new PptTask(
                rs.getLong("id"),
                rs.getString("conversation_id"),
                PptState.valueOf(rs.getString("status")),
                rs.getString("error_msg"),
                rs.getString("context_json"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    }
}
