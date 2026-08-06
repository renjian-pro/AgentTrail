package com.agenttrail.capability.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.List;

/**
 * 大文件分块 + 向量化 + 按 fileId 打标签存入向量库（issue #26）。
 *
 * <p>分块用 500 字符 / 50 重叠，批次大小 9——两个数字都对齐参考实现（dodo-agentx）已验证
 * 可用的取值，不是随手拍的。
 *
 * <p>向量化失败必须可观测：不像参考实现那样打个 warn 日志就悄悄放弃、让文件停留在
 * "看起来传成功了、实际检索不到任何东西"的半成品状态——这里直接抛
 * {@link VectorizationException}，调用方必须处理，不能假装没发生。
 */
public class FileVectorizationService {

    private static final Logger log = LoggerFactory.getLogger(FileVectorizationService.class);

    private static final int CHUNK_SIZE = 500;
    private static final int CHUNK_OVERLAP = 50;
    private static final int EMBEDDING_BATCH_SIZE = 9;
    static final String FILE_ID_METADATA_KEY = "fileId";

    private final VectorStore vectorStore;
    private final OverlapParagraphTextSplitter splitter = new OverlapParagraphTextSplitter(CHUNK_SIZE, CHUNK_OVERLAP);

    public FileVectorizationService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    /**
     * @return 实际写入的分块数
     * @throws VectorizationException 向量化失败——调用方决定是回滚整次上传还是如何提示用户，
     *                                 但绝不能悄悄吞掉
     */
    public int vectorize(long fileId, String fullText) {
        List<Document> chunks = splitter.apply(List.of(new Document(fullText)));
        for (Document chunk : chunks) {
            chunk.getMetadata().put(FILE_ID_METADATA_KEY, String.valueOf(fileId));
        }

        try {
            for (int i = 0; i < chunks.size(); i += EMBEDDING_BATCH_SIZE) {
                List<Document> batch = chunks.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, chunks.size()));
                vectorStore.add(batch);
            }
        } catch (RuntimeException failure) {
            log.error("文件 {} 向量化失败（{} 个分块）：{}", fileId, chunks.size(), failure.getMessage(), failure);
            throw new VectorizationException(
                    "文件 " + fileId + " 向量化失败（" + chunks.size() + " 个分块）：" + failure.getMessage(), failure);
        }

        log.info("文件 {} 向量化完成，共 {} 个分块", fileId, chunks.size());
        return chunks.size();
    }

    /**
     * 删除一个文件在向量库里的全部分块（文件本身被删除时联动清理）——按 {@link #vectorize}
     * 写入时打的同一个 {@code fileId} 标签过滤删除，不需要先查一遍分块 id 再逐个删。
     */
    public void deleteByFileId(long fileId) {
        Filter.Expression fileIdFilter = new FilterExpressionBuilder()
                .eq(FILE_ID_METADATA_KEY, String.valueOf(fileId))
                .build();
        vectorStore.delete(fileIdFilter);
        log.info("文件 {} 的向量分块已从向量库删除", fileId);
    }
}
