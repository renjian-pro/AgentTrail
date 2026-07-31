package com.agenttrail.web;

import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.file.FileTextParser;
import com.agenttrail.loop.file.JdbcFileStore;
import com.agenttrail.loop.multimodal.ImageDescriptionService;
import com.agenttrail.loop.rag.FileVectorizationService;
import com.agenttrail.loop.rag.RagRetrievalService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 文件问答（issue #21）的生产装配。阈值默认 5000 字符，对齐参考实现（dodo-agentx
 * {@code file.large-file-threshold}）已验证可用的默认值。超过阈值的大文件的分块入库/检索
 * 由 {@link RagConfig} 装配的协作者承担（issue #26）；图片描述由 {@link ImageDescriptionService}
 * 承担（issue #27），复用 issue #20 已经装配好的 {@code openAiChatModel} bean。
 */
@Configuration
public class FileQaConfig {

    @Bean
    public FileStore fileStore(DataSource dataSource) {
        return new JdbcFileStore(dataSource);
    }

    @Bean
    public FileTextParser fileTextParser() {
        return new FileTextParser();
    }

    @Bean
    public ImageDescriptionService imageDescriptionService(
            @Qualifier("openAiChatModel") ChatModel chatModel,
            @Value("${agenttrail.multimodal.model:qwen3-vl-plus}") String model) {
        return new ImageDescriptionService(chatModel, model);
    }

    @Bean
    public FileQaService fileQaService(FileStore fileStore, FileTextParser fileTextParser,
            FileVectorizationService fileVectorizationService, RagRetrievalService ragRetrievalService,
            ImageDescriptionService imageDescriptionService,
            @Value("${agenttrail.file.rag-threshold-chars:5000}") int ragThresholdChars) {
        return new FileQaService(fileStore, fileTextParser, fileVectorizationService, ragRetrievalService,
                imageDescriptionService, ragThresholdChars);
    }
}
