package com.agenttrail.capability.fileqa;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.IngestedFile;
import com.agenttrail.capability.file.FileTextParser;
import com.agenttrail.capability.fileqa.application.FileIngestUseCase;
import com.agenttrail.capability.fileqa.domain.Attachment;
import com.agenttrail.capability.fileqa.domain.AttachmentStatus;
import com.agenttrail.capability.fileqa.port.EmbeddingPort;
import com.agenttrail.capability.fileqa.port.FileStorePort;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class FileIngestUseCaseTest {
    @Test
    void persistsIngestingThenReadyAndEmbedsLargeText() {
        RecordingStore store = new RecordingStore();
        RecordingEmbeddings embeddings = new RecordingEmbeddings();
        FileIngestUseCase useCase = new FileIngestUseCase(store, new FileTextParser(1_000), embeddings, 3);

        IngestedFile result = useCase.ingest("u-1", "c-1", "notes.txt", "text/plain",
                new ByteArrayInputStream("abcdef".getBytes()), 6);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(store.saved.getFirst().status()).isEqualTo(AttachmentStatus.INGESTING);
        assertThat(store.readyIds).containsExactly(1L);
        assertThat(embeddings.fileIds).containsExactly(1L);
    }

    private static final class RecordingStore implements FileStorePort {
        private final List<Attachment> saved = new ArrayList<>();
        private final List<Long> readyIds = new ArrayList<>();

        @Override public long save(Attachment attachment) { saved.add(attachment); return saved.size(); }
        @Override public Optional<Attachment> findById(long id) { return Optional.empty(); }
        @Override public List<Attachment> findByConversationId(String conversationId) { return List.of(); }
        @Override public void updateParsedText(long id, String parsedText) { }
        @Override public void markReady(long id, String parsedText, byte[] rawBytes) { readyIds.add(id); }
        @Override public void linkFilesToTurn(String conversationId, java.util.List<Long> fileIds, long turnId) { }
        @Override public void delete(long id) { }
    }

    private static final class RecordingEmbeddings implements EmbeddingPort {
        private final List<Long> fileIds = new ArrayList<>();
        @Override public int embedAndStore(long fileId, String fullText) { fileIds.add(fileId); return 1; }
        @Override public void deleteByFileId(long fileId) { }
    }
}
