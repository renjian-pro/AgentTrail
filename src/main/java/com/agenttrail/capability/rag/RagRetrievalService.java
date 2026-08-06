package com.agenttrail.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.expansion.MultiQueryExpander;
import org.springframework.ai.rag.preretrieval.query.expansion.QueryExpander;
import org.springframework.ai.rag.preretrieval.query.transformation.CompressionQueryTransformer;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.agenttrail.capability.rag.FileVectorizationService.FILE_ID_METADATA_KEY;

/**
 * RAG 检索管线（issue #26）：查询压缩 → 多查询扩展（3 个改写 + 原始）→ 按 fileId 过滤的
 * PgVector 相似度检索 → 按文档 id 去重合并。管线本身照抄参考实现（dodo-agentx
 * {@code EmbeddingService#ragRetrieve}）——两份参考实现在这一段和本项目规划几乎逐字一致。
 *
 * <p>压缩/扩展用的 {@link ChatModel} 是固定的默认模型，不跟随 issue #20 的按会话模型选择——
 * 这是内部检索管线的辅助步骤，不是用户看到的那一轮回答，没有必要跟着会话切换。
 *
 * <p>不做 rerank：两份参考实现都没做，属于有意识的简化而非遗漏。
 */
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private static final int TOP_K = 5;
    private static final int EXPANDED_QUERY_COUNT = 3;

    private final VectorStore vectorStore;
    private final ChatClient chatClient;

    public RagRetrievalService(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatClient = ChatClient.builder(chatModel).build();
    }

    /** 按 fileId 过滤检索，返回去重后的分块正文列表；没有命中时返回空列表。 */
    public List<String> retrieve(long fileId, String question) {
        Query original = Query.builder().text(question).build();

        CompressionQueryTransformer compressor = CompressionQueryTransformer.builder()
                .chatClientBuilder(chatClient.mutate())
                .build();
        Query compressed = compressor.transform(original);
        log.debug("文件 {} 的 RAG 查询压缩结果：{}", fileId, compressed.text());

        QueryExpander expander = MultiQueryExpander.builder()
                .chatClientBuilder(chatClient.mutate())
                .numberOfQueries(EXPANDED_QUERY_COUNT)
                .includeOriginal(true)
                .build();
        List<Query> expandedQueries = expander.expand(compressed);

        Filter.Expression fileIdFilter = new FilterExpressionBuilder()
                .eq(FILE_ID_METADATA_KEY, String.valueOf(fileId))
                .build();

        List<String> results = new ArrayList<>();
        Set<String> seenDocumentIds = new HashSet<>();
        for (Query expandedQuery : expandedQueries) {
            List<Document> matches = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(expandedQuery.text())
                    .topK(TOP_K)
                    .filterExpression(fileIdFilter)
                    .build());
            for (Document match : matches) {
                if (seenDocumentIds.add(match.getId())) {
                    results.add(match.getText());
                }
            }
        }

        log.info("文件 {} 的 RAG 检索完成：{} 个改写查询，去重后 {} 条结果", fileId, expandedQueries.size(), results.size());
        return results;
    }
}
