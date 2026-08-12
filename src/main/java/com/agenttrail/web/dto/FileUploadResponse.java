package com.agenttrail.web.dto;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.IngestedFile;

/** {@code POST /agent/v1/files} 的响应体——直接对应 {@link IngestedFile}。 */
public record FileUploadResponse(long fileId, String fileName, FileKind kind, long sizeBytes, int parsedTextLength,
        boolean routedToRag, String ingestTaskId) {

    public FileUploadResponse(long fileId, String fileName, FileKind kind, long sizeBytes, int parsedTextLength,
            boolean routedToRag) {
        this(fileId, fileName, kind, sizeBytes, parsedTextLength, routedToRag, null);
    }

    public static FileUploadResponse from(IngestedFile ingested) {
        return new FileUploadResponse(ingested.id(), ingested.fileName(), ingested.kind(), ingested.sizeBytes(),
                ingested.parsedTextLength(), ingested.routedToRag(), null);
    }

    public static FileUploadResponse pending(long fileId, String fileName, FileKind kind, long sizeBytes,
            String ingestTaskId) {
        return new FileUploadResponse(fileId, fileName, kind, sizeBytes, 0, false, ingestTaskId);
    }
}
