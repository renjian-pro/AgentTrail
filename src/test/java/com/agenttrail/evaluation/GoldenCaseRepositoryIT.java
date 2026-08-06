package com.agenttrail.evaluation;

import com.agenttrail.support.SharedMySql;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 跑真实 MySQL（见 {@link SharedMySql}），和 {@code JdbcTraceStoreIT} 同一条规矩。 */
class GoldenCaseRepositoryIT {

    private static DataSource dataSource;
    private GoldenCaseRepository repository;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE golden_case").update();
        repository = new GoldenCaseRepository(dataSource, new ObjectMapper());
    }

    @Test
    void roundTripsAllFieldsIncludingAssertionsAndToolCallsAsJson() {
        GoldenCaseRecord record = new GoldenCaseRecord("case-1", "sql_correctness", "count rentals", "admin",
                "SELECT COUNT(*) FROM rental", List.of(Map.of("type", "tool_called", "name", "execute_sql")),
                List.of("execute_sql"), GoldenCaseRecord.SOURCE_MANUAL, null, 1_700_000_000_000L,
                1_700_000_000_000L);

        repository.insert(record);

        assertThat(repository.findById("case-1")).contains(record);
    }

    @Test
    void findByIdReturnsEmptyForAnUnknownId() {
        assertThat(repository.findById("missing")).isEqualTo(Optional.empty());
    }

    @Test
    void updateOverwritesEditableFieldsButLeavesSourceAndCreatedAtAlone() {
        GoldenCaseRecord original = new GoldenCaseRecord("case-2", "sql_correctness", "count rentals", "admin",
                null, List.of(Map.of("type", "tool_called", "name", "execute_sql")), List.of(),
                GoldenCaseRecord.SOURCE_PROMOTED, "conv-9", 1_700_000_000_000L, 1_700_000_000_000L);
        repository.insert(original);

        GoldenCaseRecord updated = new GoldenCaseRecord("case-2", "permission", "count rentals as analyst",
                "analyst_test", "SELECT COUNT(*) FROM rental",
                List.of(Map.of("type", "sql_contains_scope_filter", "column", "dept_id")), List.of(),
                original.source(), original.sourceConversationId(), original.createdAtMillis(),
                1_700_000_001_000L);

        assertThat(repository.update(updated)).isTrue();
        Optional<GoldenCaseRecord> stored = repository.findById("case-2");
        assertThat(stored).isPresent();
        assertThat(stored.get().dimension()).isEqualTo("permission");
        assertThat(stored.get().asUser()).isEqualTo("analyst_test");
        assertThat(stored.get().source()).isEqualTo(GoldenCaseRecord.SOURCE_PROMOTED);
        assertThat(stored.get().sourceConversationId()).isEqualTo("conv-9");
        assertThat(stored.get().createdAtMillis()).isEqualTo(1_700_000_000_000L);
    }

    @Test
    void updateReturnsFalseForAnUnknownId() {
        GoldenCaseRecord ghost = new GoldenCaseRecord("missing", "sql_correctness", "q", "admin", null,
                List.of(Map.of("type", "tool_called", "name", "x")), List.of(),
                GoldenCaseRecord.SOURCE_MANUAL, null, 1L, 1L);

        assertThat(repository.update(ghost)).isFalse();
    }

    @Test
    void deleteByIdRemovesTheRowAndReportsWhetherOneExisted() {
        repository.insert(new GoldenCaseRecord("case-3", "sql_correctness", "q", "admin", null,
                List.of(Map.of("type", "tool_called", "name", "x")), List.of(),
                GoldenCaseRecord.SOURCE_MANUAL, null, 1L, 1L));

        assertThat(repository.deleteById("case-3")).isTrue();
        assertThat(repository.findById("case-3")).isEmpty();
        assertThat(repository.deleteById("case-3")).isFalse();
    }

    @Test
    void findAllOrdersById() {
        repository.insert(new GoldenCaseRecord("case-b", "sql_correctness", "q", "admin", null,
                List.of(Map.of("type", "tool_called", "name", "x")), List.of(),
                GoldenCaseRecord.SOURCE_MANUAL, null, 1L, 1L));
        repository.insert(new GoldenCaseRecord("case-a", "sql_correctness", "q", "admin", null,
                List.of(Map.of("type", "tool_called", "name", "x")), List.of(),
                GoldenCaseRecord.SOURCE_MANUAL, null, 1L, 1L));

        List<GoldenCaseRecord> all = repository.findAll();

        assertThat(all).extracting(GoldenCaseRecord::id).containsExactly("case-a", "case-b");
    }
}
