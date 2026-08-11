package com.agenttrail.capability.fileqa.application;

import com.agenttrail.capability.fileqa.port.RetrievalPort;
import com.agenttrail.capability.rag.RagRetrievalService;

import java.util.List;

public final class LegacyRetrievalAdapter implements RetrievalPort {
    private final RagRetrievalService delegate;
    public LegacyRetrievalAdapter(RagRetrievalService delegate) { this.delegate = delegate; }
    @Override public List<String> retrieve(long fileId, String question) { return delegate.retrieve(fileId, question); }
}
