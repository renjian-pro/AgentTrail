package com.agenttrail.loop.file;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * {@link FileStore} 的 JDBC 落地实现——表结构见 {@code db/schema.sql} 的 {@code agent_file} 表。
 */
public class JdbcFileStore implements FileStore {

    private static final String INSERT_SQL = """
            INSERT INTO agent_file
                (user_id, conversation_id, turn_id, file_name, content_type, size_bytes, kind, parsed_text, raw_bytes, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id, user_id, conversation_id, turn_id, file_name, content_type, size_bytes, kind, parsed_text, raw_bytes, created_at
            FROM agent_file
            WHERE id = ?
            """;

    private static final String SELECT_BY_CONVERSATION_SQL = """
            SELECT id, user_id, conversation_id, turn_id, file_name, content_type, size_bytes, kind, parsed_text, raw_bytes, created_at
            FROM agent_file
            WHERE conversation_id = ?
            ORDER BY id ASC
            """;

    private static final String UPDATE_PARSED_TEXT_SQL = """
            UPDATE agent_file SET parsed_text = ? WHERE id = ?
            """;

    private static final String LINK_FILES_TO_TURN_SQL = """
            UPDATE agent_file SET turn_id = ? WHERE conversation_id = ? AND turn_id IS NULL
            """;

    private static final String DELETE_SQL = """
            DELETE FROM agent_file WHERE id = ?
            """;

    private final JdbcClient jdbcClient;

    public JdbcFileStore(@Qualifier("dataSource") DataSource dataSource) {
        this.jdbcClient = JdbcClient.create(dataSource);
    }

    @Override
    public long save(UploadedFile file) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcClient.sql(INSERT_SQL)
                .param(file.userId())
                .param(file.conversationId())
                .param(file.turnId())
                .param(file.fileName())
                .param(file.contentType())
                .param(file.sizeBytes())
                .param(file.kind().name())
                .param(file.parsedText())
                .param(file.rawBytes())
                .param(file.createdAtMillis())
                .update(keyHolder);
        return keyHolder.getKey().longValue();
    }

    @Override
    public Optional<UploadedFile> findById(long id) {
        return jdbcClient.sql(SELECT_BY_ID_SQL)
                .param(id)
                .query(JdbcFileStore::mapRow)
                .optional();
    }

    @Override
    public List<UploadedFile> findByConversationId(String conversationId) {
        return jdbcClient.sql(SELECT_BY_CONVERSATION_SQL)
                .param(conversationId)
                .query(JdbcFileStore::mapRow)
                .list();
    }

    @Override
    public void updateParsedText(long id, String parsedText) {
        jdbcClient.sql(UPDATE_PARSED_TEXT_SQL)
                .param(parsedText)
                .param(id)
                .update();
    }

    @Override
    public void linkFilesToTurn(String conversationId, long turnId) {
        jdbcClient.sql(LINK_FILES_TO_TURN_SQL)
                .param(turnId)
                .param(conversationId)
                .update();
    }

    @Override
    public void delete(long id) {
        jdbcClient.sql(DELETE_SQL)
                .param(id)
                .update();
    }

    private static UploadedFile mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        // wasNull() 只反映"最近一次读的那一列"，必须紧跟在 getLong("turn_id") 之后调用，
        // 不能等构造器里其它列都读完了再查——那时 wasNull() 早就变成反映最后一列的结果了
        long turnId = rs.getLong("turn_id");
        Long turnIdOrNull = rs.wasNull() ? null : turnId;
        return new UploadedFile(
                rs.getLong("id"),
                rs.getString("user_id"),
                rs.getString("conversation_id"),
                turnIdOrNull,
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                FileKind.valueOf(rs.getString("kind")),
                rs.getString("parsed_text"),
                rs.getBytes("raw_bytes"),
                rs.getLong("created_at"));
    }
}
