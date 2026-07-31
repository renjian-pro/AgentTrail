package com.agenttrail.web;

import com.agenttrail.loop.file.IngestedFile;

/** {@code POST /agent/v1/files} 的响应体——直接对应 {@link IngestedFile}。 */
public record FileUploadResponse(long fileId, String fileName, long sizeBytes, int parsedTextLength,
        boolean routedToRag) {

    static FileUploadResponse from(IngestedFile ingested) {
        return new FileUploadResponse(ingested.id(), ingested.fileName(), ingested.sizeBytes(),
                ingested.parsedTextLength(), ingested.routedToRag());
    }
}
