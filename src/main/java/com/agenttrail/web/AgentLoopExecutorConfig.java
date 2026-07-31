package com.agenttrail.web;

import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.task.AgentTaskManager;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * V1 引擎（{@code loop.core.AgentLoopExecutor}）的生产装配——issue #20 起不再是单一模型的单例，
 * 而是按模型标识可选的一批执行器（见 {@link AgentLoopExecutorFactory}）。默认模型是
 * {@code qwen-plus}（{@code spring-ai-starter-model-openai} 根据 {@code spring.ai.openai.*}
 * 配置自动装配的 bean 名为 {@code openAiChatModel}，走 DashScope 的 OpenAI 兼容模式），
 * {@code deepseek-chat} 作为第二个可选模型保留（{@code spring-ai-starter-model-deepseek}
 * 装配的 bean 名为 {@code deepSeekChatModel}）。
 *
 * <p>这里仍然只接了裸引擎——没工具/暂停恢复/追踪审计/分层记忆，见
 * {@code docs/architecture.md} 第四节的扩展模式。
 */
@Configuration
public class AgentLoopExecutorConfig {

    /** 会话单飞注册必须全局共享一份，不能每个模型各建一份，否则同一会话换模型问单飞检测会失效。 */
    @Bean
    public AgentTaskManager agentTaskManager() {
        return new AgentTaskManager();
    }

    @Bean
    public AgentLoopExecutorFactory agentLoopExecutorFactory(
            @Qualifier("deepSeekChatModel") ChatModel deepSeekChatModel,
            @Qualifier("openAiChatModel") ChatModel qwenChatModel,
            AgentTaskManager agentTaskManager) {
        List<RegisteredModel> models = List.of(
                new RegisteredModel("deepseek-chat", deepSeekChatModel, ThinkingMode.REASONING_CONTENT),
                // qwen-plus 是非思考变体，先按 DISABLED 处理——等真实 DASHSCOPE_API_KEY 到位后要实测校正
                new RegisteredModel("qwen-plus", qwenChatModel, ThinkingMode.DISABLED));
        return new AgentLoopExecutorFactory(models, "qwen-plus", agentTaskManager);
    }
}
