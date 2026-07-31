package com.agenttrail.loop.file;

import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.multimodal.ImageDescriptionService;
import com.agenttrail.loop.multimodal.support.RecordingSyncChatModel;
import com.agenttrail.loop.rag.FileVectorizationService;
import com.agenttrail.loop.rag.RagRetrievalService;
import com.agenttrail.loop.rag.VectorizationException;
import com.agenttrail.loop.rag.support.RecordingVectorStore;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileQaServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    private final FileStore fileStore = new InMemoryFileStore();
    private final FileTextParser parser = new FileTextParser();
    private final RecordingVectorStore vectorStore = new RecordingVectorStore();

    private FileQaService serviceWithThreshold(int threshold) {
        return serviceWithThreshold(threshold, new ImageDescriptionService(new RecordingSyncChatModel(), "vl-model"));
    }

    private FileQaService serviceWithThreshold(int threshold, ImageDescriptionService imageDescriptionService) {
        FileVectorizationService vectorizationService = new FileVectorizationService(vectorStore);
        // 这里的 RAG 测试都不带问题（question=null/blank），contentFor 短路在调用检索管线之前，
        // 所以这个 ChatModel 永远不会真正被调用——用 ScriptedChatModel 只是满足构造签名
        RagRetrievalService retrievalService = new RagRetrievalService(vectorStore, new ScriptedChatModel());
        return new FileQaService(fileStore, parser, vectorizationService, retrievalService, imageDescriptionService,
                threshold, FIXED_CLOCK);
    }

    @Test
    void ingestsASmallFileAndRoutesItDirectlyInsteadOfToRag() {
        FileQaService service = serviceWithThreshold(100);

        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        assertThat(ingested.fileName()).isEqualTo("small.txt");
        assertThat(ingested.kind()).isEqualTo(FileKind.TEXT);
        assertThat(ingested.sizeBytes()).isEqualTo(11);
        assertThat(ingested.parsedTextLength()).isEqualTo("hello world".length());
        assertThat(ingested.routedToRag()).isFalse();
        assertThat(vectorStore.allAdded()).as("小文件不应该触发向量化").isEmpty();
    }

    @Test
    void ingestsALargeFileFlagsItForRagAndVectorizesIt() {
        FileQaService service = serviceWithThreshold(5);

        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        assertThat(ingested.routedToRag()).isTrue();
        assertThat(vectorStore.allAdded()).as("大文件应该在 ingest 时就完成向量化").isNotEmpty();
    }

    @Test
    void aVectorizationFailureDuringIngestPropagatesInsteadOfBeingSwallowed() {
        vectorStore.failNextAddWith(new RuntimeException("embedding provider unavailable"));
        FileQaService service = serviceWithThreshold(5);

        assertThatThrownBy(() -> service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40))
                .isInstanceOf(VectorizationException.class);
    }

    @Test
    void contentForReturnsTheFullParsedTextWhenWithinThreshold() {
        FileQaService service = serviceWithThreshold(100);
        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        assertThat(service.contentFor(ingested.id(), null)).isEqualTo("hello world");
    }

    @Test
    void contentForReturnsAPlaceholderAskingForAQuestionWhenOverThresholdAndNoQuestionGiven() {
        FileQaService service = serviceWithThreshold(5);
        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        String content = service.contentFor(ingested.id(), null);

        assertThat(content).contains("文件过大").doesNotContain("this text is longer");
    }

    @Test
    void storesTheFullParsedTextRegardlessOfThresholdSoLaterRagCanUseIt() {
        FileQaService service = serviceWithThreshold(5);
        IngestedFile ingested = service.ingest("conv-1", "large.txt", "text/plain",
                inputStreamOf("this text is longer than the threshold"), 40);

        UploadedFile stored = fileStore.findById(ingested.id()).orElseThrow();
        assertThat(stored.parsedText()).isEqualTo("this text is longer than the threshold");
    }

    @Test
    void contentForRejectsAnUnknownFileId() {
        FileQaService service = serviceWithThreshold(100);

        assertThatThrownBy(() -> service.contentFor(999L, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("999");
    }

    @Test
    void savedFileHasNoTurnIdYetAndUsesTheInjectedClockForCreatedAt() {
        FileQaService service = serviceWithThreshold(100);
        IngestedFile ingested = service.ingest("conv-1", "small.txt", "text/plain",
                inputStreamOf("hello world"), 11);

        UploadedFile stored = fileStore.findById(ingested.id()).orElseThrow();
        assertThat(stored.turnId()).isNull();
        assertThat(stored.createdAtMillis()).isEqualTo(FIXED_CLOCK.millis());
    }

    @Test
    void ingestingAnImageSkipsTextParsingAndVectorizationEntirely() {
        FileQaService service = serviceWithThreshold(5);

        IngestedFile ingested = service.ingest("conv-1", "photo.png", "image/png",
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);

        assertThat(ingested.kind()).isEqualTo(FileKind.IMAGE);
        assertThat(ingested.parsedTextLength()).isZero();
        assertThat(ingested.routedToRag()).isFalse();
        assertThat(vectorStore.allAdded()).as("图片不应该被分块向量化").isEmpty();

        UploadedFile stored = fileStore.findById(ingested.id()).orElseThrow();
        assertThat(stored.parsedText()).as("图片描述是懒加载的，上传时还没有").isNull();
        assertThat(stored.rawBytes()).containsExactly(1, 2, 3);
    }

    @Test
    void contentForAnImageCallsTheVisionModelOnFirstAskAndCachesTheDescription() {
        RecordingSyncChatModel visionChatModel = new RecordingSyncChatModel(text("一只猫"));
        FileQaService service = serviceWithThreshold(5, new ImageDescriptionService(visionChatModel, "vl-model"));
        IngestedFile ingested = service.ingest("conv-1", "cat.png", "image/png",
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 3);

        String firstAnswer = service.contentFor(ingested.id(), null);
        String secondAnswer = service.contentFor(ingested.id(), null);

        assertThat(firstAnswer).isEqualTo("一只猫");
        assertThat(secondAnswer).isEqualTo("一只猫");
        assertThat(visionChatModel.callCount()).as("第二次问应该命中缓存，不重复调用模型").isEqualTo(1);
        assertThat(fileStore.findById(ingested.id()).orElseThrow().parsedText()).isEqualTo("一只猫");
    }

    private static ByteArrayInputStream inputStreamOf(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
