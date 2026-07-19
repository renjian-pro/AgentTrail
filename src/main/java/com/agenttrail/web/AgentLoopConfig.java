package com.agenttrail.web;

import com.agenttrail.loop.AgentLoop;
import com.agenttrail.loop.deepseek.DeepSeekLlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AgentLoopConfig {

    @Bean
    public AgentLoop agentLoop(
            @Value("${deepseek.api-key}") String apiKey,
            @Value("${deepseek.base-url}") String baseUrl,
            @Value("${deepseek.model}") String model) {
        DeepSeekLlmClient llmClient = new DeepSeekLlmClient(apiKey, baseUrl, model);
        return new AgentLoop(llmClient, List.of(), 5);
    }
}
