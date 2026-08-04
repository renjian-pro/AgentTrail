package com.agenttrail.web;

import com.agenttrail.loop.file.FileQaService;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.tools.FileContentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AgentLoopExecutorConfigTest {

    /**
     * 应用启动时 CapabilityConversationService 需要 JSON 序列化器。这个最小上下文刻意不启用
     * MVC 自动配置，确保配置类自己提供了这项运行时依赖，而不是偶然依赖某个 starter 的传递行为。
     */
    @Test
    void providesTheObjectMapperRequiredByCapabilityConversationHistory() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(AgentLoopExecutorConfig.class, InfrastructureStubs.class);
            context.refresh();

            assertThat(context.getBean(ObjectMapper.class)).isNotNull();
            assertThat(context.getBean(CapabilityConversationService.class)).isNotNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class InfrastructureStubs {

        @Bean
        DataSource dataSource() {
            return mock(DataSource.class);
        }

        @Bean("deepSeekChatModel")
        ChatModel deepSeekChatModel() {
            return mock(ChatModel.class);
        }

        @Bean("openAiChatModel")
        ChatModel openAiChatModel() {
            return mock(ChatModel.class);
        }

        // 真实构造而不是 mock(FileContentTool.class)：mock 的 toolCallback() 默认返回 null，
        // 会在 AgentLoopExecutorFactory 里 List.of(null) 直接 NPE——这个类本身很轻量，
        // 真实构造（配一个 mock 的 FileQaService）比再去 stub 一个 mock 更简单也更不容易踩坑。
        @Bean
        FileContentTool fileContentTool() {
            return new FileContentTool(mock(FileQaService.class));
        }

        @Bean
        FileStore fileStore() {
            return mock(FileStore.class);
        }
    }
}
