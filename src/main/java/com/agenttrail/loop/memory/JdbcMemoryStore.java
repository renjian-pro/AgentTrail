package com.agenttrail.loop.memory;

import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;

/**
 * {@link MemoryStore} 的 JDBC 落地实现——每一行对应一条记忆，表结构见 {@code db/schema.sql}
 * 的 {@code agent_memory} 表。
 */
public class JdbcMemoryStore implements MemoryStore {

    private static final String INSERT_SQL = """
            INSERT INTO agent_memory (user_id, type, content, created_at)
            VALUES (?, ?, ?, ?)
            """;

    private static final String SELECT_BY_USER_SQL = """
            SELECT user_id, type, content, created_at
            FROM agent_memory
            WHERE user_id = ?
            ORDER BY id ASC
            """;

    private final JdbcClient jdbcClient;

    public JdbcMemoryStore(DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public void save(MemoryItem item) {
        jdbcClient.sql(INSERT_SQL)
                .param(item.userId())
                .param(item.type().name())
                .param(item.content())
                .param(item.createdAtMillis())
                .update();
    }

    @Override
    public List<MemoryItem> findByUserId(String userId) {
        return jdbcClient.sql(SELECT_BY_USER_SQL)
                .param(userId)
                .query((rs, rowNum) -> new MemoryItem(
                        rs.getString("user_id"),
                        MemoryType.valueOf(rs.getString("type")),
                        rs.getString("content"),
                        rs.getLong("created_at")))
                .list();
    }
}
