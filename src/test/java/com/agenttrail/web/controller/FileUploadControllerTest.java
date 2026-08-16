package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.file.FileTextParser;
import com.agenttrail.capability.file.FileUploadPolicy;
import com.agenttrail.capability.file.InMemoryFileStore;
import com.agenttrail.capability.fileqa.application.FileContentQueryUseCase;
import com.agenttrail.capability.fileqa.application.FileIngestTaskWorker;
import com.agenttrail.capability.fileqa.application.FileIngestUseCase;
import com.agenttrail.capability.fileqa.application.FileRetrievalUseCase;
import com.agenttrail.capability.fileqa.application.LegacyEmbeddingAdapter;
import com.agenttrail.capability.fileqa.application.LegacyFileStoreAdapter;
import com.agenttrail.capability.fileqa.application.LegacyRetrievalAdapter;
import com.agenttrail.capability.rag.FileVectorizationService;
import com.agenttrail.capability.rag.RagRetrievalService;
import com.agenttrail.capability.rag.support.RecordingVectorStore;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.web.dto.FileContentResponse;
import com.agenttrail.web.dto.FileUploadResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * 装配的是**生产真正使用的那条路径**（{@code FileIngestUseCase}/{@code FileContentQueryUseCase}）。
 *
 * <p>这一点是 Phase -1 修正的：此前这个类构造的是 {@code FileUploadController(FileQaService)}
 * 兼容构造函数，而 Spring 注入的从来是另一条 UseCase 路径——下面那几个 IDOR / fail-open 回归断言
 * 因此一直守在一条生产不执行的分支上。兼容构造函数已删除，控制器只剩一条路径。
 */
class FileUploadControllerTest {

    private static final int RAG_THRESHOLD_CHARS = 100;

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final LegacyFileStoreAdapter fileStore = new LegacyFileStoreAdapter(new InMemoryFileStore());
    private final FileIngestUseCase ingest = new FileIngestUseCase(fileStore, new FileTextParser(),
            new LegacyEmbeddingAdapter(new FileVectorizationService(vectorStore)), RAG_THRESHOLD_CHARS);
    private final FileContentQueryUseCase contentQuery = new FileContentQueryUseCase(fileStore,
            new FileRetrievalUseCase(new LegacyRetrievalAdapter(
                    new RagRetrievalService(vectorStore, new ScriptedChatModel())), RAG_THRESHOLD_CHARS));

    /**
     * 阈值设成 {@link Long#MAX_VALUE}：这些用例断言的是同步上传路径的行为，
     * 异步 reserve/worker 分支由 {@code FileIngestUseCase} 自己的测试覆盖。
     */
    private final FileUploadController controller = new FileUploadController(ingest, contentQuery,
            new FileIngestTaskWorker(ingest, Runnable::run), FileUploadPolicy.defaults(), Long.MAX_VALUE);

    @Test
    void uploadParsesAndPersistsThenReturnsTheRoutingDecision() {
        MockMultipartFile file = textFile();

        FileUploadResponse response;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            response = controller.upload(file, "conv-1");
        }

        assertThat(response.fileName()).isEqualTo("note.txt");
        assertThat(response.sizeBytes()).isEqualTo(11);
        assertThat(response.parsedTextLength()).isEqualTo("hello world".length());
        assertThat(response.routedToRag()).isFalse();
    }

    @Test
    void contentReturnsWhatTheUploadProducedToTheOwningUser() {
        long fileId = uploadAs("user-1");

        FileContentResponse content;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            content = controller.content(fileId, null);
        }

        assertThat(content.content()).isEqualTo("hello world");
    }

    /** Regression test for the IDOR this endpoint used to have: ownership must be checked per caller, not skipped. */
    @Test
    void contentRejectsAFileThatBelongsToADifferentUser() {
        long fileId = uploadAs("user-1");

        try (MockedStatic<StpUtil> stp = loggedInAs("user-2")) {
            assertThatThrownBy(() -> controller.content(fileId, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    /**
     * Regression test for the fail-open bug: a missing/unresolvable userId (no Sa-Token request
     * context at all — the state this method falls back to when there's genuinely no caller
     * identity) must be rejected, not treated as "skip the ownership check".
     */
    @Test
    void contentRejectsWhenThereIsNoCallerIdentity() {
        long fileId = uploadAs("user-1");

        // No mocked StpUtil context here at all — matches what currentUserId() actually falls back
        // to outside of a real HTTP request (see its RuntimeException-catch branch).
        assertThatThrownBy(() -> controller.content(fileId, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void contentRejectsAnUnknownFileIdAsNotFound() {
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            assertThatThrownBy(() -> controller.content(999L, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    /**
     * 回归测试：之前前端"×"只是本地过滤掉，从没调用过任何后端接口——服务端那份记录一直都在，
     * 会话继续提问时模型和 RAG 检索照样能看到"已删除"的文件。现在必须真的能删掉，删掉之后
     * 通过 content() 就再也读不到了。
     */
    @Test
    void deleteRemovesTheFileSoItCanNoLongerBeRead() {
        long fileId = uploadAs("user-1");

        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            controller.delete(fileId);
        }

        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            assertThatThrownBy(() -> controller.content(fileId, null))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    /** Regression test for the same IDOR shape as content(): ownership must be checked per caller. */
    @Test
    void deleteRejectsAFileThatBelongsToADifferentUser() {
        long fileId = uploadAs("user-1");

        try (MockedStatic<StpUtil> stp = loggedInAs("user-2")) {
            assertThatThrownBy(() -> controller.delete(fileId))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }

        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            assertThat(controller.content(fileId, null).content()).isEqualTo("hello world");
        }
    }

    @Test
    void deleteRejectsAnUnknownFileIdAsNotFound() {
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            assertThatThrownBy(() -> controller.delete(999L))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("404");
        }
    }

    private long uploadAs(String userId) {
        try (MockedStatic<StpUtil> stp = loggedInAs(userId)) {
            return controller.upload(textFile(), "conv-1").fileId();
        }
    }

    private static MockMultipartFile textFile() {
        return new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
    }

    private static MockedStatic<StpUtil> loggedInAs(String userId) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsString).thenReturn(userId);
        return stp;
    }
}
