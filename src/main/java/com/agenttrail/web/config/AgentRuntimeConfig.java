package com.agenttrail.web.config;
import com.agenttrail.web.controller.AgentController;

import com.agenttrail.legacy.V0.AgentRuntime;
import com.agenttrail.legacy.V0.AgentScopeRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配 V0（{@code AgentScopeRuntime}）供 {@link AgentController} 使用；V1 的装配见 {@link AgentLoopExecutorConfig}。 */
@Configuration
public class AgentRuntimeConfig {

    @Bean
    public AgentRuntime agentRuntime(
            @Value("${spring.ai.deepseek.api-key}") String apiKey,
            @Value("${spring.ai.deepseek.base-url}") String baseUrl,
            @Value("${spring.ai.deepseek.chat.options.model}") String model) {
        return new AgentScopeRuntime(apiKey, baseUrl, model, 5);
    }
}
