package com.agenttrail.loop.file;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FilePromptFormatterTest {

    /**
     * 回归测试：空字符串曾经是"没有文件"的信号，但模型没法区分"这一段没插入"和"插入了但
     * 就是空的"——沉默会被当成不确定，实测会让模型为了回答数据/图表类问题去猜一个不存在的
     * fileId 调用 load_file_content。必须显式声明"没有文件"，把沉默换成明确的否定信号。
     */
    @Test
    void statesExplicitlyThatThereAreNoFilesInsteadOfStayingSilent() {
        assertThat(FilePromptFormatter.formatSection(List.of()))
                .contains("没有已上传的文件")
                .contains("不要调用 load_file_content");
        assertThat(FilePromptFormatter.formatSection(null))
                .contains("没有已上传的文件")
                .contains("不要调用 load_file_content");
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
