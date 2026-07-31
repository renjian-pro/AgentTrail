package com.agenttrail.web;

import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileTextParser;
import com.agenttrail.loop.file.InMemoryFileStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileUploadControllerTest {

    private final FileUploadController controller = new FileUploadController(
            new FileQaService(new InMemoryFileStore(), new FileTextParser(), 100));

    @Test
    void uploadParsesAndPersistsThenReturnsTheRoutingDecision() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));

        FileUploadResponse response = controller.upload(file, "conv-1");

        assertThat(response.fileName()).isEqualTo("note.txt");
        assertThat(response.sizeBytes()).isEqualTo(11);
        assertThat(response.parsedTextLength()).isEqualTo("hello world".length());
        assertThat(response.routedToRag()).isFalse();
    }

    @Test
    void contentReturnsWhatTheUploadProduced() {
        MockMultipartFile file = new MockMultipartFile("file", "note.txt", "text/plain",
                "hello world".getBytes(StandardCharsets.UTF_8));
        FileUploadResponse uploaded = controller.upload(file, "conv-1");

        FileContentResponse content = controller.content(uploaded.fileId());

        assertThat(content.content()).isEqualTo("hello world");
    }

    @Test
    void contentRejectsAnUnknownFileIdAsNotFound() {
        assertThatThrownBy(() -> controller.content(999L))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }
}
