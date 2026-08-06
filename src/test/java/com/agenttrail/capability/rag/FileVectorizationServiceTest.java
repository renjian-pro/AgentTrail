package com.agenttrail.capability.rag;

import com.agenttrail.capability.rag.support.RecordingVectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileVectorizationServiceTest {

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final FileVectorizationService service = new FileVectorizationService(vectorStore);

    @Test
    void everyStoredChunkIsTaggedWithTheFileId() {
        service.vectorize(42L, "a".repeat(1200));

        assertThat(vectorStore.allAdded())
                .isNotEmpty()
                .allSatisfy(chunk -> assertThat(chunk.getMetadata())
                        .containsEntry(FileVectorizationService.FILE_ID_METADATA_KEY, "42"));
    }

    @Test
    void returnsTheNumberOfChunksActuallyWritten() {
        int chunkCount = service.vectorize(1L, "a".repeat(1200));

        assertThat(chunkCount).isEqualTo(vectorStore.allAdded().size());
        assertThat(chunkCount).isGreaterThan(1);
    }

    @Test
    void aVectorStoreFailurePropagatesAsAnObservableVectorizationExceptionInsteadOfBeingSwallowed() {
        vectorStore.failNextAddWith(new RuntimeException("embedding provider unavailable"));

        assertThatThrownBy(() -> service.vectorize(7L, "a".repeat(1200)))
                .isInstanceOf(VectorizationException.class)
                .hasMessageContaining("7")
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    void aSmallDocumentStillProducesAtLeastOneChunk() {
        int chunkCount = service.vectorize(1L, "short text");

        assertThat(chunkCount).isEqualTo(1);
        assertThat(vectorStore.allAdded()).extracting(Document::getText).containsExactly("short text");
    }
}
