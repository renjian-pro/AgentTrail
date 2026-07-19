package com.agenttrail.web;

import com.agenttrail.runtime.AgentRuntime;
import com.agenttrail.runtime.agentscope.AgentScopeRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRuntimeConfig {

    @Bean
    public AgentRuntime agentRuntime(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        return new AgentScopeRuntime(apiKey, baseUrl, model, 5);
    }
}
