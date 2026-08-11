package com.agenttrail.capability.fileqa.port;

public interface EmbeddingPort {
    int embedAndStore(long fileId, String fullText);
    void deleteByFileId(long fileId);
}
