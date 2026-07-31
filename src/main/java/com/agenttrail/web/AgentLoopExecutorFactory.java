package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.task.AgentTaskManager;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 按模型标识构造/缓存 {@link AgentLoopExecutor}（issue #20）——每个注册的模型对应一个执行器，
 * 在装配时一次性建好并缓存，不是每次请求现建。除了 {@code ChatModel} 和
 * {@link com.agenttrail.loop.model.ThinkingMode} 随模型变化，其余协作者（当前只有共享的
 * {@link AgentTaskManager}，后续接入工具/暂停恢复/追踪审计/分层记忆时同理）在所有模型之间
 * 完全一致——尤其 {@code AgentTaskManager} 必须全局唯一，否则同一会话换个模型问，
 * 单飞检测会在"每个模型各有一份"的情况下失效。
 *
 * <p>不支持、不保证同一会话中途切换模型：调用方要在同一个会话里保持用同一个模型标识，
 * 这里既不做跨模型历史兼容性校验，也不做"同一会话只能用一个模型"的强制约束——
 * 一致性是调用方的责任，不是这一层要解决的问题。
 */
public class AgentLoopExecutorFactory {

    private final Map<String, AgentLoopExecutor> executorsByModelId;
    private final String defaultModelId;

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager) {
        if (models.stream().noneMatch(model -> model.id().equals(defaultModelId))) {
            throw new IllegalArgumentException("默认模型 " + defaultModelId + " 不在注册的模型列表里");
        }
        this.executorsByModelId = models.stream().collect(Collectors.toMap(
                RegisteredModel::id,
                model -> AgentLoopExecutor.builder(model.chatModel(), List.of(), 10)
                        .taskManager(taskManager)
                        .thinkingMode(model.thinkingMode())
                        .build()));
        this.defaultModelId = defaultModelId;
    }

    /** @param modelId 为 null 或空串时使用默认模型；未注册的标识直接抛异常，不做静默兜底 */
    public AgentLoopExecutor forModel(String modelId) {
        String resolvedId = (modelId == null || modelId.isBlank()) ? defaultModelId : modelId;
        AgentLoopExecutor executor = executorsByModelId.get(resolvedId);
        if (executor == null) {
            throw new IllegalArgumentException("未知的模型标识: " + resolvedId);
        }
        return executor;
    }
}
