package com.agenttrail.loop.file;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilePromptFormatterTest {

    @Test
    void returnsEmptyStringWhenThereAreNoFiles() {
        assertThat(FilePromptFormatter.formatSection(List.of())).isEmpty();
        assertThat(FilePromptFormatter.formatSection(null)).isEmpty();
    }

    /** 没有这句强约束，模型看到"有个文件"这行字之后完全没有理由去调用 load_file_content。 */
    @Test
    void forcesTheModelToCallTheToolInsteadOfAnsweringFromMemory() {
        UploadedFile file = new UploadedFile(12L, "user-1", "conv-1", null, "report.pdf", "application/pdf",
                1024, FileKind.TEXT, "...", null, 1L);

        String section = FilePromptFormatter.formatSection(List.of(file));

        assertThat(section).contains("load_file_content");
        assertThat(section).contains("禁止在没有调用工具的情况下");
        assertThat(section).contains("report.pdf").contains("fileId=12");
    }
}
