package com.agenttrail.capability.file;

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

    /** 只取提示词区块要用的四列，谓词一并下推——见 {@code FileStore#findVisibleForPrompt}。 */
    private static final String SELECT_VISIBLE_FOR_PROMPT_TEMPLATE = """
            SELECT id, turn_id, file_name, kind
            FROM agent_file
            WHERE conversation_id = ? AND (turn_id IS NOT NULL%s)
            ORDER BY id ASC
            """;

    private static final String UPDATE_PARSED_TEXT_SQL = """
            UPDATE agent_file SET parsed_text = ? WHERE id = ?
            """;

    /**
     * 三个条件各有各的职责，一个都不能省（issue #110）：
     * <ul>
     *   <li>{@code id IN (...)} —— 只绑用户这一轮显式带上来的，不再是"扫一遍会话里所有没归属的"
     *   <li>{@code conversation_id = ?} —— 让跨会话绑定在 SQL 层就不可能发生，比上层校验可靠
     *   <li>{@code turn_id IS NULL} —— 幂等保证，同时挡住"把已经属于第 3 轮的文件改绑到第 7 轮"
     * </ul>
     * 占位符个数随 fileIds 变，所以这里是个模板，由 {@code linkFilesToTurn} 拼出来。
     */
    private static final String LINK_FILES_TO_TURN_SQL_TEMPLATE = """
            UPDATE agent_file SET turn_id = ?
            WHERE conversation_id = ? AND turn_id IS NULL AND id IN (%s)
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
    public List<UploadedFile> findVisibleForPrompt(String conversationId, List<Long> requestedFileIds) {
        boolean hasRequested = requestedFileIds != null && !requestedFileIds.isEmpty();
        // 没带文件时整个 OR 分支不拼——空的 IN () 在 MySQL 上是语法错误
        String clause = hasRequested
                ? " OR id IN (" + String.join(", ", java.util.Collections.nCopies(requestedFileIds.size(), "?")) + ")"
                : "";
        var statement = jdbcClient.sql(SELECT_VISIBLE_FOR_PROMPT_TEMPLATE.formatted(clause)).param(conversationId);
        if (hasRequested) {
            for (Long fileId : requestedFileIds) {
                statement = statement.param(fileId);
            }
        }
        return statement.query(JdbcFileStore::mapPromptRow).list();
    }

    /** 元数据投影：parsedText/rawBytes 一律 null，见接口说明。 */
    private static UploadedFile mapPromptRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        long turnId = rs.getLong("turn_id");
        return new UploadedFile(rs.getLong("id"), null, null,
                rs.wasNull() ? null : turnId, rs.getString("file_name"), null, 0L,
                FileKind.valueOf(rs.getString("kind")), null, null, 0L);
    }

    @Override
    public void updateParsedText(long id, String parsedText) {
        jdbcClient.sql(UPDATE_PARSED_TEXT_SQL)
                .param(parsedText)
                .param(id)
                .update();
    }

    @Override
    public void linkFilesToTurn(String conversationId, List<Long> fileIds, long turnId) {
        if (fileIds == null || fileIds.isEmpty()) {
            // 这一轮没带文件。**必须早返回**——空的 IN () 在 MySQL 上是语法错误，
            // 而且"没带文件"是绝大多数轮次的常态，不该产生任何写入
            return;
        }
        String placeholders = String.join(", ", java.util.Collections.nCopies(fileIds.size(), "?"));
        var statement = jdbcClient.sql(LINK_FILES_TO_TURN_SQL_TEMPLATE.formatted(placeholders))
                .param(turnId)
                .param(conversationId);
        for (Long fileId : fileIds) {
            statement = statement.param(fileId);
        }
        statement.update();
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
