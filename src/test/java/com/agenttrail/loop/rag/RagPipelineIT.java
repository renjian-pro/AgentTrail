package com.agenttrail.loop.rag;

import com.agenttrail.support.SharedMySql;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 PgVector 容器 + 真实 DashScope embedding/chat 跑通"分块入库 → 检索"的完整链路
 * （issue #26 验收标准明确要求，不接受 mock）。MySQL 用 {@link SharedMySql}（应用其余组件也需要
 * 一个可用的 DataSource 才能装配起来），Postgres/DashScope 都用 application.properties 里
 * 已经配置好的真实地址（见 RagConfig/application.properties）。
 */
@SpringBootTest
class RagPipelineIT {

    /** 每个测试方法用互不相同的 fileId，天然隔离，不需要在向量库里清表。 */
    private static final AtomicLong NEXT_FILE_ID = new AtomicLong(System.currentTimeMillis());

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SharedMySql::jdbcUrl);
        registry.add("spring.datasource.username", SharedMySql::username);
        registry.add("spring.datasource.password", SharedMySql::password);
    }

    @Autowired
    private FileVectorizationService vectorizationService;

    @Autowired
    private RagRetrievalService retrievalService;

    @Test
    void vectorizesALargeDocumentAndRetrievesTheChunkContainingThePlantedFact() {
        long fileId = NEXT_FILE_ID.getAndIncrement();
        String plantedFact = "该文件的专属校验码是 ZQPX-9137，任何人问起校验码都应该给出这个值。";
        String document = paddedDocumentContaining(plantedFact);

        int chunkCount = vectorizationService.vectorize(fileId, document);
        assertThat(chunkCount).isGreaterThan(1);

        List<String> results = retrievalService.retrieve(fileId, "这份文件的专属校验码是多少？");

        assertThat(results).isNotEmpty();
        assertThat(String.join("\n", results)).contains("ZQPX-9137");
    }

    @Test
    void filtersByFileIdSoOneFilesChunksDoNotLeakIntoAnotherFilesResults() {
        long fileIdA = NEXT_FILE_ID.getAndIncrement();
        long fileIdB = NEXT_FILE_ID.getAndIncrement();
        String factA = "文件 A 的专属校验码是 APPLE-1234。";
        String factB = "文件 B 的专属校验码是 ORANGE-5678。";

        vectorizationService.vectorize(fileIdA, paddedDocumentContaining(factA));
        vectorizationService.vectorize(fileIdB, paddedDocumentContaining(factB));

        List<String> resultsForA = retrievalService.retrieve(fileIdA, "这份文件的专属校验码是多少？");

        String combined = String.join("\n", resultsForA);
        assertThat(combined).contains("APPLE-1234");
        assertThat(combined).doesNotContain("ORANGE-5678");
    }

    /** 拼一段足够长的正文（多段落，超过分块阈值），把要检索的事实埋在中间一段。 */
    private static String paddedDocumentContaining(String plantedFact) {
        String filler = "这是一段用来撑长度的填充文本，内容本身和检索的问题无关，只是为了让分块器切出多个片段。";
        StringBuilder document = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            document.append(filler).append(" 第").append(i).append("段填充内容。\n");
        }
        document.append(plantedFact).append('\n');
        for (int i = 6; i < 12; i++) {
            document.append(filler).append(" 第").append(i).append("段填充内容。\n");
        }
        return document.toString();
    }
}
