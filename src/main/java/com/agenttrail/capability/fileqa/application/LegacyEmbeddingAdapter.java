package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.fileqa.port.EmbeddingPort;
import com.agenttrail.capability.rag.FileVectorizationService;

public final class LegacyEmbeddingAdapter implements EmbeddingPort {
    private final FileVectorizationService delegate;
    public LegacyEmbeddingAdapter(FileVectorizationService delegate) { this.delegate = delegate; }
    @Override public int embedAndStore(long fileId, String fullText) { return delegate.vectorize(fileId, fullText); }
    @Override public void deleteByFileId(long fileId) { delegate.deleteByFileId(fileId); }
}
