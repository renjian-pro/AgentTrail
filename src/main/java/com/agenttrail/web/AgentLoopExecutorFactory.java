package com.agenttrail.web;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 按模型标识（+ 是否挂载联网搜索）构造/缓存 {@link AgentLoopExecutor}（issue #20/#22）——
 * 不带搜索的执行器每个注册模型一份，装配时一次性建好；带搜索的执行器懒加载——
 * {@link TavilySearchToolProvider#toolCallbacks()} 第一次真正被请求时才建连 MCP 客户端，
 * 不能在这个工厂的构造函数里就调用它，否则会把"联网搜索懒加载"的承诺在装配阶段就破坏掉。
 *
 * <p>除了 {@code ChatModel} 和 {@link com.agenttrail.loop.model.ThinkingMode} 随模型变化、
 * 工具列表随是否挂载搜索变化，其余协作者（当前只有共享的 {@link AgentTaskManager}）在所有
 * 变体之间完全一致——尤其 {@code AgentTaskManager} 必须全局唯一，否则同一会话换个模型/换搜索
 * 开关问，单飞检测会在"每个变体各有一份"的情况下失效。
 *
 * <p>不支持、不保证同一会话中途切换模型：调用方要在同一个会话里保持用同一个模型标识，
 * 这里既不做跨模型历史兼容性校验，也不做"同一会话只能用一个模型"的强制约束——
 * 一致性是调用方的责任，不是这一层要解决的问题。
 */
public class AgentLoopExecutorFactory {

    private final Map<String, RegisteredModel> modelsById;
    private final Map<String, AgentLoopExecutor> plainExecutorsByModelId;
    private final Map<String, AgentLoopExecutor> webSearchExecutorsByModelId = new ConcurrentHashMap<>();
    private final String defaultModelId;
    private final AgentTaskManager taskManager;
    /** 传 null 表示这套装配完全不提供联网搜索——{@code webSearchEnabled=true} 时静默退化成不带搜索。 */
    private final TavilySearchToolProvider webSearchToolProvider;

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider) {
        if (models.stream().noneMatch(model -> model.id().equals(defaultModelId))) {
            throw new IllegalArgumentException("默认模型 " + defaultModelId + " 不在注册的模型列表里");
        }
        this.modelsById = models.stream().collect(Collectors.toMap(RegisteredModel::id, model -> model));
        this.plainExecutorsByModelId = models.stream().collect(Collectors.toMap(
                RegisteredModel::id,
                model -> AgentLoopExecutor.builder(model.chatModel(), List.of(), 10)
                        .taskManager(taskManager)
                        .thinkingMode(model.thinkingMode())
                        .build()));
        this.defaultModelId = defaultModelId;
        this.taskManager = taskManager;
        this.webSearchToolProvider = webSearchToolProvider;
    }

    /** @param modelId 为 null 或空串时使用默认模型；未注册的标识直接抛异常，不做静默兜底 */
    public AgentLoopExecutor forModel(String modelId) {
        return forModel(modelId, false);
    }

    /**
     * @param webSearchEnabled 为 true 且联网搜索可用时，返回的执行器工具列表里带着搜索工具；
     *                         为 false，或搜索工具本次不可用（key 缺失/连接失败）时，
     *                         返回的执行器工具列表里压根没有它——不是"注册了但暂时用不了"
     */
    public AgentLoopExecutor forModel(String modelId, boolean webSearchEnabled) {
        String resolvedId = resolve(modelId);
        if (!webSearchEnabled || webSearchToolProvider == null) {
            return requireExecutor(plainExecutorsByModelId, resolvedId);
        }

        AgentLoopExecutor cached = webSearchExecutorsByModelId.get(resolvedId);
        if (cached != null) {
            return cached;
        }

        List<ToolCallback> webSearchTools = webSearchToolProvider.toolCallbacks();
        RegisteredModel model = modelsById.get(resolvedId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + resolvedId);
        }
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model.chatModel(), webSearchTools, 10)
                .taskManager(taskManager)
                .thinkingMode(model.thinkingMode())
                .build();
        // 只缓存"搜索工具真的挂上了"的结果——如果这次是降级（工具列表为空），
        // 不缓存，让下一次请求有机会在 Tavily 恢复后重新拿到一个真正带搜索的执行器
        if (!webSearchTools.isEmpty()) {
            webSearchExecutorsByModelId.put(resolvedId, executor);
        }
        return executor;
    }

    private String resolve(String modelId) {
        return (modelId == null || modelId.isBlank()) ? defaultModelId : modelId;
    }

    private static AgentLoopExecutor requireExecutor(Map<String, AgentLoopExecutor> executors, String modelId) {
        AgentLoopExecutor executor = executors.get(modelId);
        if (executor == null) {
            throw new IllegalArgumentException("未知的模型标识: " + modelId);
        }
        return executor;
    }
}
