package com.agenttrail.web;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileTextParser;
import com.agenttrail.loop.file.InMemoryFileStore;
import com.agenttrail.loop.multimodal.ImageDescriptionService;
import com.agenttrail.loop.multimodal.support.RecordingSyncChatModel;
import com.agenttrail.loop.rag.FileVectorizationService;
import com.agenttrail.loop.rag.RagRetrievalService;
import com.agenttrail.loop.rag.support.RecordingVectorStore;
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

    private static MockedStatic<StpUtil> loggedInAs(String userId) {
        MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
        stp.when(StpUtil::isLogin).thenReturn(true);
        stp.when(StpUtil::getLoginIdAsString).thenReturn(userId);
        return stp;
    }
}
