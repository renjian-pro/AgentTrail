package com.agenttrail.web;

import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.file.FileTextParser;
import com.agenttrail.loop.file.JdbcFileStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 文件问答（issue #21）的生产装配。阈值默认 5000 字符，对齐参考实现（dodo-agentx
 * {@code file.large-file-threshold}）已验证可用的默认值。
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
    public FileQaService fileQaService(FileStore fileStore, FileTextParser fileTextParser,
            @Value("${agenttrail.file.rag-threshold-chars:5000}") int ragThresholdChars) {
        return new FileQaService(fileStore, fileTextParser, ragThresholdChars);
    }
}
