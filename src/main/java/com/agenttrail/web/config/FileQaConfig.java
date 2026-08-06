package com.agenttrail.web.config;

import com.agenttrail.capability.file.FileQaService;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.capability.file.FileTextParser;
import com.agenttrail.capability.file.JdbcFileStore;
import com.agenttrail.capability.file.multimodal.ImageDescriptionService;
import com.agenttrail.loop.tools.FileContentTool;
import com.agenttrail.capability.rag.FileVectorizationService;
import com.agenttrail.capability.rag.RagRetrievalService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 文件问答（issue #21）的生产装配。阈值默认 5000 字符，对齐研读过的参考实现里已验证可用的
 * 默认值。超过阈值的大文件的分块入库/检索
 * 由 {@link RagConfig} 装配的协作者承担（issue #26）；图片描述由 {@link ImageDescriptionService}
 * 承担（issue #27），复用 issue #20 已经装配好的 {@code openAiChatModel} bean。
 */
@Configuration
public class FileQaConfig {

    @Bean
    public FileStore fileStore(@Qualifier("dataSource") DataSource dataSource) {
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

    /** 模型读取文件内容的唯一入口——纯本地 DB/服务调用，不需要像联网搜索/图表那样懒加载。 */
    @Bean
    public FileContentTool fileContentTool(FileQaService fileQaService) {
        return new FileContentTool(fileQaService);
    }
}
