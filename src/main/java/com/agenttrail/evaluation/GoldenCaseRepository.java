package com.agenttrail.evaluation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code golden_case} 表的读写。建表语句在 {@code db/schema.sql}。 */
public class GoldenCaseRepository {
    private static final TypeReference<List<Map<String, Object>>> ASSERTIONS_TYPE = new TypeReference<>() { };
    private static final TypeReference<List<String>> TOOL_CALLS_TYPE = new TypeReference<>() { };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public GoldenCaseRepository(@Qualifier("dataSource") DataSource dataSource, ObjectMapper objectMapper) {
        this.jdbc = JdbcClient.create(dataSource);
        this.objectMapper = objectMapper;
    }

    public List<GoldenCaseRecord> findAll() {
        return jdbc.sql("SELECT id, dimension, question, as_user, reference_sql, assertions_json, "
                        + "expected_tool_calls_json, source, source_conversation_id, created_at, updated_at "
                        + "FROM golden_case ORDER BY id")
                .query(this::mapRow)
                .list();
    }

    public Optional<GoldenCaseRecord> findById(String id) {
        return jdbc.sql("SELECT id, dimension, question, as_user, reference_sql, assertions_json, "
                        + "expected_tool_calls_json, source, source_conversation_id, created_at, updated_at "
                        + "FROM golden_case WHERE id = ?")
                .param(id)
                .query(this::mapRow)
                .optional();
    }

    public void insert(GoldenCaseRecord record) {
        jdbc.sql("INSERT INTO golden_case (id, dimension, question, as_user, reference_sql, assertions_json, "
                        + "expected_tool_calls_json, source, source_conversation_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(record.id(), record.dimension(), record.question(), record.asUser(), record.referenceSql(),
                        writeJson(record.assertions()), writeJson(record.expectedToolCalls()), record.source(),
                        record.sourceConversationId(), record.createdAtMillis(), record.updatedAtMillis())
                .update();
    }

    public boolean update(GoldenCaseRecord record) {
        return jdbc.sql("UPDATE golden_case SET dimension = ?, question = ?, as_user = ?, reference_sql = ?, "
                        + "assertions_json = ?, expected_tool_calls_json = ?, updated_at = ? WHERE id = ?")
                .params(record.dimension(), record.question(), record.asUser(), record.referenceSql(),
                        writeJson(record.assertions()), writeJson(record.expectedToolCalls()),
                        record.updatedAtMillis(), record.id())
                .update() > 0;
    }

    public boolean deleteById(String id) {
        return jdbc.sql("DELETE FROM golden_case WHERE id = ?").param(id).update() > 0;
    }

    private GoldenCaseRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new GoldenCaseRecord(
                rs.getString("id"), rs.getString("dimension"), rs.getString("question"), rs.getString("as_user"),
                rs.getString("reference_sql"), readJson(rs.getString("assertions_json"), ASSERTIONS_TYPE),
                readJson(rs.getString("expected_tool_calls_json"), TOOL_CALLS_TYPE),
                rs.getString("source"), rs.getString("source_conversation_id"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private <T> T readJson(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception failure) {
            throw new IllegalStateException("golden_case 里存了一段解析不出来的 JSON: " + json, failure);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception failure) {
            throw new IllegalStateException("Golden Case 序列化失败", failure);
        }
    }
}
