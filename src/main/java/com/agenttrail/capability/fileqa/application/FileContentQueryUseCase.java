package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.fileqa.domain.Attachment;
import com.agenttrail.capability.fileqa.domain.AttachmentStatus;
import com.agenttrail.capability.fileqa.port.FileStorePort;

import java.util.NoSuchElementException;

public final class FileContentQueryUseCase {
    private final FileStorePort files;
    private final FileRetrievalUseCase retrieval;

    public FileContentQueryUseCase(FileStorePort files, FileRetrievalUseCase retrieval) {
        this.files = files;
        this.retrieval = retrieval;
    }

    public boolean belongsToUser(long fileId, String userId) {
        return files.findById(fileId).map(file -> userId != null && userId.equals(file.userId())).orElse(false);
    }

    public boolean belongsToConversation(long fileId, String conversationId) {
        return files.findById(fileId).map(file -> conversationId != null && conversationId.equals(file.conversationId()))
                .orElse(false);
    }

    public String contentFor(long fileId, String question) {
        Attachment file = files.findById(fileId)
                .orElseThrow(() -> new NoSuchElementException("Unknown file: " + fileId));
        if (file.status() == AttachmentStatus.INGESTING) {
            return "文件仍在处理中，请稍后重试";
        }
        if (file.status() == AttachmentStatus.FAILED) {
            return "文件处理失败：" + file.errorCode();
        }
        if (file.kind() == FileKind.IMAGE) {
            return file.parsedText() == null ? "图片描述尚未生成" : file.parsedText();
        }
        return retrieval.contentFor(file, question);
    }
}
