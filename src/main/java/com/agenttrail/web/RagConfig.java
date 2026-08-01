package com.agenttrail.web;

import com.agenttrail.loop.rag.FileVectorizationService;
import com.agenttrail.loop.rag.RagRetrievalService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 大文件 RAG 检索（issue #26）的生产装配。PgVector 是独立于 {@code agent_session} 那个
 * MySQL 的另一个数据库，复用这台机器上已经在跑的 {@code pgvector/pgvector:pg16} 容器。
 *
 * <p>这里的 Postgres 连接池刻意不注册成 Spring {@code DataSource} bean——应用里已经有一个
 * MySQL 的 {@code DataSource} bean（{@code spring.datasource.*}），再注册一个同类型的 bean
 * 会让所有不带 {@code @Qualifier} 的 {@code DataSource} 注入点（{@code JdbcTraceStore} 等）
 * 变得有歧义。连接池只是这个方法内部的一个局部对象，不暴露给容器。
 */
@Configuration
public class RagConfig {

    private static final String VECTOR_TABLE_NAME = "agent_file_chunk";
    /** 向量维度对齐 DashScope {@code text-embedding-v4} 的输出维度。 */
    private static final int EMBEDDING_DIMENSIONS = 1024;

    @Bean
    PgVectorDataSource pgVectorDataSource(
            @Value("${agenttrail.pgvector.url}") String url,
            @Value("${agenttrail.pgvector.username}") String username,
            @Value("${agenttrail.pgvector.password}") String password,
            @Value("${agenttrail.pgvector.maximum-pool-size:4}") int maximumPoolSize) {
        HikariConfig poolConfig = new HikariConfig();
        poolConfig.setJdbcUrl(url);
        poolConfig.setUsername(username);
        poolConfig.setPassword(password);
        poolConfig.setDriverClassName("org.postgresql.Driver");
        poolConfig.setPoolName("pgvector-pool");
        poolConfig.setMaximumPoolSize(maximumPoolSize);
        poolConfig.setMinimumIdle(0);
        return new PgVectorDataSource(new HikariDataSource(poolConfig));
    }

    @Bean
    public VectorStore fileChunkVectorStore(EmbeddingModel embeddingModel, PgVectorDataSource pgVectorDataSource) {
        PgVectorStore store = PgVectorStore.builder(pgVectorDataSource.jdbcTemplate(), embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .distanceType(PgVectorStore.PgDistanceType.COSINE_DISTANCE)
                .indexType(PgVectorStore.PgIndexType.HNSW)
                .vectorTableName(VECTOR_TABLE_NAME)
                .initializeSchema(true)
                .build();
        try {
            store.afterPropertiesSet();
        } catch (Exception initializationFailure) {
            throw new IllegalStateException("PgVectorStore 初始化失败", initializationFailure);
        }
        return store;
    }

    @Bean
    public FileVectorizationService fileVectorizationService(VectorStore fileChunkVectorStore) {
        return new FileVectorizationService(fileChunkVectorStore);
    }

    /** 查询压缩/多查询扩展用固定的默认模型，不跟随 issue #20 的按会话模型选择。 */
    @Bean
    public RagRetrievalService ragRetrievalService(VectorStore fileChunkVectorStore,
            @Qualifier("openAiChatModel") ChatModel chatModel) {
        return new RagRetrievalService(fileChunkVectorStore, chatModel);
    }

    static final class PgVectorDataSource implements AutoCloseable {
        private final HikariDataSource dataSource;

        PgVectorDataSource(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }

        JdbcTemplate jdbcTemplate() {
            return new JdbcTemplate(dataSource);
        }

        @Override
        public void close() {
            dataSource.close();
        }
    }
}
