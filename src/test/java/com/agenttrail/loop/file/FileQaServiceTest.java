package com.agenttrail.loop.file;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileQaServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final FileStore fileStore = new InMemoryFileStore();
    private final FileTextParser parser = new FileTextParser();

    @Test
    void ingestsASmallFileAndRoutesItDirectlyInsteadOfToRag() {
        FileQaService service = new FileQaService(fileStore, parser, 100, FIXED_CLOCK);

        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        assertThat(ingested.fileName()).isEqualTo("small.txt");
        assertThat(ingested.sizeBytes()).isEqualTo(11);
        assertThat(ingested.parsedTextLength()).isEqualTo("hello world".length());
        assertThat(ingested.routedToRag()).isFalse();
    }

    @Test
    void ingestsALargeFileAndFlagsItForRagInstead() {
        FileQaService service = new FileQaService(fileStore, parser, 5, FIXED_CLOCK);

        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        assertThat(ingested.routedToRag()).isTrue();
    }

    @Test
    void contentForReturnsTheFullParsedTextWhenWithinThreshold() {
        FileQaService service = new FileQaService(fileStore, parser, 100, FIXED_CLOCK);
        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        assertThat(service.contentFor(ingested.id())).isEqualTo("hello world");
    }

    @Test
    void contentForReturnsARagPlaceholderInsteadOfTheFullTextWhenOverThreshold() {
        FileQaService service = new FileQaService(fileStore, parser, 5, FIXED_CLOCK);
        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        String content = service.contentFor(ingested.id());

        assertThat(content).contains("文件过大").doesNotContain("this text is longer");
    }

    @Test
    void storesTheFullParsedTextRegardlessOfThresholdSoLaterRagCanUseIt() {
        FileQaService service = new FileQaService(fileStore, parser, 5, FIXED_CLOCK);
        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        UploadedFile stored = fileStore.findById(ingested.id()).orElseThrow();
        assertThat(stored.parsedText()).isEqualTo("this text is longer than the threshold");
    }

    @Test
    void contentForRejectsAnUnknownFileId() {
        FileQaService service = new FileQaService(fileStore, parser, 100, FIXED_CLOCK);

        assertThatThrownBy(() -> service.contentFor(999L))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
    }

    @Test
    void savedFileHasNoTurnIdYetAndUsesTheInjectedClockForCreatedAt() {
        FileQaService service = new FileQaService(fileStore, parser, 100, FIXED_CLOCK);
        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        UploadedFile stored = fileStore.findById(ingested.id()).orElseThrow();
        assertThat(stored.turnId()).isNull();
        assertThat(stored.createdAtMillis()).isEqualTo(FIXED_CLOCK.millis());
    }

    private static ByteArrayInputStream inputStreamOf(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
