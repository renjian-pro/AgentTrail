package com.agenttrail.web;

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
    }
}
