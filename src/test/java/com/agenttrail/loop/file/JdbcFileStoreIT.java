package com.agenttrail.loop.file;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 跑真实 MySQL（见 {@link SharedMySql}），不用 H2——和 {@code JdbcSessionStoreIT} 同一条规矩。 */
class JdbcFileStoreIT {

    private static DataSource dataSource;
    private JdbcFileStore store;

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
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_file").update();
        store = new JdbcFileStore(dataSource);
    }

    @Test
    void savingReturnsAGeneratedIdAndTheRecordCanBeFoundById() {
        long id = store.save(new UploadedFile(null, "conv-1", null, "note.txt", "text/plain",
                11, "hello world", 1_700_000_000_000L));

        Optional<UploadedFile> found = store.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().fileName()).isEqualTo("note.txt");
        assertThat(found.get().parsedText()).isEqualTo("hello world");
    }

    @Test
    void turnIdIsNullUntilExplicitlyBackfilled() {
        long id = store.save(new UploadedFile(null, "conv-1", null, "note.txt", "text/plain",
                11, "hello world", 1_700_000_000_000L));

        assertThat(store.findById(id).orElseThrow().turnId()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(store.findById(999L)).isEmpty();
    }

    @Test
    void findByConversationIdReturnsAllFilesForThatConversationInUploadOrder() {
        store.save(new UploadedFile(null, "conv-1", null, "first.txt", "text/plain", 5, "first", 1L));
        store.save(new UploadedFile(null, "conv-1", null, "second.txt", "text/plain", 6, "second", 2L));
        store.save(new UploadedFile(null, "conv-2", null, "other.txt", "text/plain", 5, "other", 3L));

        List<UploadedFile> files = store.findByConversationId("conv-1");

        assertThat(files).extracting(UploadedFile::fileName).containsExactly("first.txt", "second.txt");
    }

    @Test
    void storesTheFullParsedTextEvenWhenItIsLarge() {
        String largeText = "x".repeat(20_000);
        long id = store.save(new UploadedFile(null, "conv-1", null, "large.txt", "text/plain",
                20_000, largeText, 1_700_000_000_000L));

        assertThat(store.findById(id).orElseThrow().parsedText()).hasSize(20_000);
    }

    @Test
    void contentTypeCanBeNull() {
        long id = store.save(new UploadedFile(null, "conv-1", null, "unknown.bin", null,
                5, "", 1_700_000_000_000L));

        assertThat(store.findById(id).orElseThrow().contentType()).isNull();
    }
}
