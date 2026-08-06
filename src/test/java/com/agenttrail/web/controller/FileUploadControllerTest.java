package com.agenttrail.web.controller;
import com.agenttrail.web.dto.FileUploadResponse;
import com.agenttrail.web.dto.FileContentResponse;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.capability.file.FileQaService;
import com.agenttrail.capability.file.FileTextParser;
import com.agenttrail.capability.file.InMemoryFileStore;
import com.agenttrail.capability.file.multimodal.ImageDescriptionService;
import com.agenttrail.capability.file.multimodal.support.RecordingSyncChatModel;
import com.agenttrail.capability.rag.FileVectorizationService;
import com.agenttrail.capability.rag.RagRetrievalService;
import com.agenttrail.capability.rag.support.RecordingVectorStore;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

class FileUploadControllerTest {

    private final RecordingVectorStore vectorStore = new RecordingVectorStore();
    private final FileUploadController controller = new FileUploadController(
            new FileQaService(new InMemoryFileStore(), new FileTextParser(),
                    new FileVectorizationService(vectorStore),
                    new RagRetrievalService(vectorStore, new ScriptedChatModel()),
                    new ImageDescriptionService(new RecordingSyncChatModel(), "vl-model"),
                    100));

    @Test
    void uploadParsesAndPersistsThenReturnsTheRoutingDecision() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));

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
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        long fileId;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            fileId = controller.upload(file, "conv-1").fileId();
        }

        FileContentResponse content;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            content = controller.content(fileId, null);
        }

        assertThat(content.content()).isEqualTo("hello world");
    }

    /** Regression test for the IDOR this endpoint used to have: ownership must be checked per caller, not skipped. */
    @Test
    void contentRejectsAFileThatBelongsToADifferentUser() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        long fileId;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            fileId = controller.upload(file, "conv-1").fileId();
        }

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
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        long fileId;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            fileId = controller.upload(file, "conv-1").fileId();
        }

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
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        long fileId;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            fileId = controller.upload(file, "conv-1").fileId();
        }

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
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        long fileId;
        try (MockedStatic<StpUtil> stp = loggedInAs("user-1")) {
            fileId = controller.upload(file, "conv-1").fileId();
        }

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

    private static MockedStatic<StpUtil> loggedInAs(String userId) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsString).thenReturn(userId);
        return stp;
    }
}
