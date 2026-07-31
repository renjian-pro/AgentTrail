package com.agenttrail.loop.rag.support;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link VectorStore} 的测试替身：记录每次 {@link #add} 收到的文档（含 batch 分界），
 * 可以设定下一次 {@code add} 抛出指定异常，用于验证向量化失败时的可观测行为。
 */
public class RecordingVectorStore implements VectorStore {

    private final List<Document> allAdded = new ArrayList<>();
    private final List<List<Document>> addedBatches = new ArrayList<>();
    private RuntimeException failureToThrowOnNextAdd;

    public void failNextAddWith(RuntimeException failure) {
        this.failureToThrowOnNextAdd = failure;
    }

    @Override
    public void add(List<Document> documents) {
        if (failureToThrowOnNextAdd != null) {
            RuntimeException toThrow = failureToThrowOnNextAdd;
            failureToThrowOnNextAdd = null;
            throw toThrow;
        }
        addedBatches.add(List.copyOf(documents));
        allAdded.addAll(documents);
    }

    @Override
    public void delete(List<String> idList) {
    }

    @Override
    public void delete(Filter.Expression filterExpression) {
    }

    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        return List.of();
    }

    public List<Document> allAdded() {
        return List.copyOf(allAdded);
    }

    public List<List<Document>> addedBatches() {
        return List.copyOf(addedBatches);
    }
}
