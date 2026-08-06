package com.agenttrail.capability.file;

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

    private static UploadedFile textFile(String conversationId, String fileName, long sizeBytes, String parsedText,
            long createdAtMillis) {
        return new UploadedFile(null, conversationId, null, fileName, "text/plain", sizeBytes, FileKind.TEXT,
                parsedText, null, createdAtMillis);
    }

    @Test
    void savingReturnsAGeneratedIdAndTheRecordCanBeFoundById() {
        long id = store.save(textFile("conv-1", "note.txt", 11, "hello world", 1_700_000_000_000L));

        Optional<UploadedFile> found = store.findById(id);

        assertThat(found).isPresent();
        assertThat(found.get().id()).isEqualTo(id);
        assertThat(found.get().fileName()).isEqualTo("note.txt");
        assertThat(found.get().parsedText()).isEqualTo("hello world");
        assertThat(found.get().kind()).isEqualTo(FileKind.TEXT);
    }

    @Test
    void turnIdIsNullUntilExplicitlyBackfilled() {
        long id = store.save(textFile("conv-1", "note.txt", 11, "hello world", 1_700_000_000_000L));

        assertThat(store.findById(id).orElseThrow().turnId()).isNull();
    }

    @Test
    void returnsEmptyForAnUnknownId() {
        assertThat(store.findById(999L)).isEmpty();
    }

    @Test
    void findByConversationIdReturnsAllFilesForThatConversationInUploadOrder() {
        store.save(textFile("conv-1", "first.txt", 5, "first", 1L));
        store.save(textFile("conv-1", "second.txt", 6, "second", 2L));
        store.save(textFile("conv-2", "other.txt", 5, "other", 3L));

        List<UploadedFile> files = store.findByConversationId("conv-1");

        assertThat(files).extracting(UploadedFile::fileName).containsExactly("first.txt", "second.txt");
    }

    @Test
    void storesTheFullParsedTextEvenWhenItIsLarge() {
        String largeText = "x".repeat(20_000);
        long id = store.save(textFile("conv-1", "large.txt", 20_000, largeText, 1_700_000_000_000L));

        assertThat(store.findById(id).orElseThrow().parsedText()).hasSize(20_000);
    }

    @Test
    void contentTypeCanBeNull() {
        UploadedFile file = new UploadedFile(null, "conv-1", null, "unknown.bin", null, 5, FileKind.TEXT, "",
                null, 1_700_000_000_000L);
        long id = store.save(file);

        assertThat(store.findById(id).orElseThrow().contentType()).isNull();
    }

    @Test
    void anImageFileStoresItsKindAndRawBytesWithNoParsedTextYet() {
        byte[] rawBytes = {1, 2, 3, 4};
        UploadedFile image = new UploadedFile(null, "conv-1", null, "photo.png", "image/png", 4, FileKind.IMAGE,
                null, rawBytes, 1_700_000_000_000L);

        long id = store.save(image);
        UploadedFile found = store.findById(id).orElseThrow();

        assertThat(found.kind()).isEqualTo(FileKind.IMAGE);
        assertThat(found.parsedText()).isNull();
        assertThat(found.rawBytes()).containsExactly(1, 2, 3, 4);
    }

    @Test
    void updateParsedTextWritesBackTheLazilyComputedImageDescription() {
        UploadedFile image = new UploadedFile(null, "conv-1", null, "photo.png", "image/png", 4, FileKind.IMAGE,
                null, new byte[]{1, 2, 3}, 1_700_000_000_000L);
        long id = store.save(image);

        store.updateParsedText(id, "一只猫坐在窗台上");

        assertThat(store.findById(id).orElseThrow().parsedText()).isEqualTo("一只猫坐在窗台上");
    }

    @Test
    void deleteRemovesOnlyTheTargetedRow() {
        long toDelete = store.save(textFile("conv-1", "first.txt", 5, "first", 1L));
        long toKeep = store.save(textFile("conv-1", "second.txt", 6, "second", 2L));

        store.delete(toDelete);

        assertThat(store.findById(toDelete)).isEmpty();
        assertThat(store.findById(toKeep)).isPresent();
        assertThat(store.findByConversationId("conv-1")).extracting(UploadedFile::fileName).containsExactly("second.txt");
    }

    @Test
    void deletingAnUnknownIdIsANoOp() {
        store.delete(999L);
    }
}
