package com.agenttrail.web.dto;

import com.agenttrail.capability.file.FileKind;
import com.agenttrail.capability.file.IngestedFile;

/** {@code POST /agent/v1/files} 的响应体——直接对应 {@link IngestedFile}。 */
public record FileUploadResponse(long fileId, String fileName, FileKind kind, long sizeBytes, int parsedTextLength,
        boolean routedToRag) {

    public static FileUploadResponse from(IngestedFile ingested) {
        return new FileUploadResponse(ingested.id(), ingested.fileName(), ingested.kind(), ingested.sizeBytes(),
                ingested.parsedTextLength(), ingested.routedToRag());
    }
}
