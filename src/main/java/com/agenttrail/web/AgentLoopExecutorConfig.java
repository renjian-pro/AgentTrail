package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）的最小生产装配——只接了裸引擎，不带工具/暂停恢复/
 * 追踪审计/分层记忆这些可选机制。{@code ChatModel} 由 {@code spring-ai-starter-model-deepseek}
 * 根据 {@code spring.ai.deepseek.*} 配置自动装配，这里直接注入使用，不用手写 HTTP 客户端
 * （对照 V0 的 {@code AgentScopeRuntime}/{@code DeepSeekLlmClient}，见 {@link AgentRuntimeConfig}）。
 *
 * <p>后续要开工具/暂停恢复/追踪审计/分层记忆等机制，照 {@code AgentLoopExecutor.builder(...)}
 * 链式调用对应的 {@code .toolCatalog(...)}/{@code .pauseConfig(...)}/{@code .traceStore(...)}/
 * {@code .memoryStore(...)} 即可（见 {@code docs/architecture.md} 第四节"扩展模式"）。
 */
@Configuration
public class AgentLoopExecutorConfig {

    @Bean
    public AgentLoopExecutor agentLoopExecutor(ChatModel chatModel) {
        return AgentLoopExecutor.builder(chatModel, List.of(), 10).build();
    }
}
