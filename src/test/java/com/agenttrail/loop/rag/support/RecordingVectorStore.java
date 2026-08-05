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
 *
 * <p>{@link #delete(Filter.Expression)} 真的会从 {@link #allAdded} 里移除匹配的文档——只支持
 * {@code FileVectorizationService} 实际会传入的那种简单形态（单个 metadata 字段的 EQ 表达式），
 * 够用来断言"删除文件后它的分块确实从向量库里没了"，不需要一个完整的表达式求值器。
 */
public class RecordingVectorStore implements VectorStore {

    private final List<Document> allAdded = new ArrayList<>();
    private final List<List<Document>> addedBatches = new ArrayList<>();
    private final List<Filter.Expression> deleteFilterExpressions = new ArrayList<>();
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
        deleteFilterExpressions.add(filterExpression);
        if (!(filterExpression.left() instanceof Filter.Key key) || !(filterExpression.right() instanceof Filter.Value value)) {
            return;
        }
        allAdded.removeIf(document -> value.value().equals(document.getMetadata().get(key.key())));
        addedBatches.replaceAll(batch -> batch.stream()
                .filter(document -> !value.value().equals(document.getMetadata().get(key.key())))
                .toList());
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

    public List<Filter.Expression> deleteFilterExpressions() {
        return List.copyOf(deleteFilterExpressions);
    }
}
