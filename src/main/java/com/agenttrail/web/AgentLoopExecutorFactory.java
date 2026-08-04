package com.agenttrail.web;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.FileContentTool;
import com.agenttrail.loop.tools.chart.ChartToolProvider;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.search.ToolSearchConfig;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
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

    private static final String QWEN_PLUS = "qwen-plus";
    private static final String TOOL_CALLING_COMPATIBLE_MODEL = "deepseek-chat";

    private final Map<String, RegisteredModel> modelsById;
    private final Map<String, AgentLoopExecutor> plainExecutorsByModelId;
    private final Map<String, AgentLoopExecutor> webSearchExecutorsByModelId = new ConcurrentHashMap<>();
    private final Map<String, AgentLoopExecutor> analyticsExecutorsByModelId = new ConcurrentHashMap<>();
    /** key = modelId + "|" + webSearchEnabled，见 {@link #forModelWithCharts}。 */
    private final Map<String, AgentLoopExecutor> chartExecutorsByKey = new ConcurrentHashMap<>();
    private final String defaultModelId;
    private final AgentTaskManager taskManager;
    /** 传 null 表示这套装配完全不提供联网搜索——{@code webSearchEnabled=true} 时静默退化成不带搜索。 */
    private final TavilySearchToolProvider webSearchToolProvider;
    /** 传 null 表示这套装配完全不提供图表生成——{@link #forModelWithCharts} 时静默退化成不带图表。 */
    private final ChartToolProvider chartToolProvider;
    private final TurnPersistenceHook persistenceHook;
    /**
     * 不像 webSearch/chart 那样按会话/开关条件——文件内容读取没有隐私或成本上的权衡要留给调用方
     * 决定，且是纯本地 DB 调用，不需要懒加载，所以每个执行器变体（plain/webSearch/chart）
     * 都无条件带上它。传 null 表示这套装配完全不提供文件问答工具。
     */
    private final List<ToolCallback> baseTools;
    /**
     * 传 null 表示这套装配完全不提供文件问答——{@code AgentLoopExecutor.buildFileSection} 据此
     * 短路返回空串，"会话文件"区块不会出现在系统提示词里，{@link FileContentTool} 就算挂着，
     * 模型也无从知道有哪些 fileId 可以调用（这正是 {@code FileContentTool} 本身没问题、
     * 但生产环境里模型一直"看不到文件"的真正原因——{@link #buildExecutor} 之前没把这个传给
     * {@code AgentLoopExecutor.Builder}）。
     */
    private final FileStore fileStore;
    private final AnalyticsToolProvider analyticsToolProvider;

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider) {
        this(models, defaultModelId, taskManager, webSearchToolProvider, null);
    }

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider,
            ChartToolProvider chartToolProvider) {
        this(models, defaultModelId, taskManager, webSearchToolProvider, chartToolProvider, null);
    }

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider,
            ChartToolProvider chartToolProvider, TurnPersistenceHook persistenceHook) {
        this(models, defaultModelId, taskManager, webSearchToolProvider, chartToolProvider, persistenceHook, null);
    }

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider,
            ChartToolProvider chartToolProvider, TurnPersistenceHook persistenceHook,
            FileContentTool fileContentTool) {
        this(models, defaultModelId, taskManager, webSearchToolProvider, chartToolProvider, persistenceHook,
                fileContentTool, null);
    }

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider,
            ChartToolProvider chartToolProvider, TurnPersistenceHook persistenceHook,
            FileContentTool fileContentTool, FileStore fileStore) {
        this(models, defaultModelId, taskManager, webSearchToolProvider, chartToolProvider, persistenceHook,
                fileContentTool, fileStore, null);
    }

    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId,
            AgentTaskManager taskManager, TavilySearchToolProvider webSearchToolProvider,
            ChartToolProvider chartToolProvider, TurnPersistenceHook persistenceHook,
            FileContentTool fileContentTool, FileStore fileStore,
            AnalyticsToolProvider analyticsToolProvider) {
        if (models.stream().noneMatch(model -> model.id().equals(defaultModelId))) {
            throw new IllegalArgumentException("默认模型 " + defaultModelId + " 不在注册的模型列表里");
        }
        this.modelsById = models.stream().collect(Collectors.toMap(RegisteredModel::id, model -> model));
        this.defaultModelId = defaultModelId;
        // buildExecutor() 读的是字段 this.taskManager，必须先赋值再拿它去建 plainExecutorsByModelId——
        // 之前这两行顺序反了，plainExecutorsByModelId 里的每个执行器都是拿着一个还没赋值（null）的
        // taskManager 建出来的，只是懒加载的 webSearch/chart 执行器建得晚，运行时才会踩到 NPE
        this.taskManager = taskManager;
        this.webSearchToolProvider = webSearchToolProvider;
        this.chartToolProvider = chartToolProvider;
        this.persistenceHook = persistenceHook;
        this.fileStore = fileStore;
        this.analyticsToolProvider = analyticsToolProvider;
        this.baseTools = fileContentTool != null ? List.of(fileContentTool.toolCallback()) : List.of();
        // baseTools 现在可能非空（文件工具无条件挂载），所以这里也必须经过
        // resolveToolCallingModel 那道 qwen-plus→deepseek-chat 的安全切换——之前这里直接
        // buildExecutor(model, ...) 用的是请求方自己的 ChatModel，跳过了这道开关，
        // 是踩坑点 #78a（OpenAiChatModel 合并流式 tool_call 分片时对 Optional 直接 get()，
        // 第三方库兼容性 bug）復现的真正原因：qwen-plus 的 plain 执行器第一次真的带上工具，
        // 但从没被换到 deepseek-chat。
        this.plainExecutorsByModelId = models.stream().collect(Collectors.toMap(
                RegisteredModel::id,
                model -> {
                    String effectiveId = resolveToolCallingModel(model.id(), !baseTools.isEmpty());
                    RegisteredModel effectiveModel = modelsById.get(effectiveId);
                    return buildExecutor(effectiveModel, baseTools, null, true);
                }));
    }

    private AgentLoopExecutor buildExecutor(RegisteredModel model, List<ToolCallback> tools, ContextPolicy contextPolicy) {
        return buildExecutor(model, tools, contextPolicy, true);
    }

    /**
     * @param persist 是否挂 {@link #persistenceHook}——{@code false} 用于 DeepResearch 这类内部编排
     *                （critique/plan/summarize 等子调用）：它们不是用户在应用层面发起的一轮对话，
     *                落进 {@code agent_session} 只会把会话历史侧栏污染成一堆内部子提示词
     *                （见 {@link #forInternalOrchestration}）。
     */
    private AgentLoopExecutor buildExecutor(RegisteredModel model, List<ToolCallback> tools, ContextPolicy contextPolicy,
            boolean persist) {
        AgentLoopExecutor.Builder builder = AgentLoopExecutor.builder(model.chatModel(), tools, 10)
                .taskManager(taskManager)
                .thinkingMode(model.thinkingMode())
                .persistenceHook(persist ? persistenceHook : null)
                .fileStore(fileStore);
        if (contextPolicy != null) {
            builder.contextPolicy(contextPolicy);
        }
        return builder.build();
    }

    /** @param modelId 为 null 或空串时使用默认模型；未注册的标识直接抛异常，不做静默兜底 */
    public AgentLoopExecutor forModel(String modelId) {
        return forModel(modelId, false);
    }

    /** DataAgent 专用执行器：只挂载分析白名单和图表工具，不复用文件/Shell 工具。 */
    public AgentLoopExecutor forAnalytics(String modelId) {
        if (analyticsToolProvider == null) {
            throw new IllegalStateException("分析能力未启用，请联系管理员配置分析数据源");
        }
        String resolvedId = resolve(modelId);
        AgentLoopExecutor cached = analyticsExecutorsByModelId.get(resolvedId);
        if (cached != null) {
            return cached;
        }
        List<ToolCallback> residentTools = chartToolProvider == null
                ? List.of() : chartToolProvider.toolCallbacks();
        String effectiveId = resolveToolCallingModel(resolvedId, true);
        RegisteredModel model = modelsById.get(effectiveId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + effectiveId);
        }
        ToolCatalog catalog = ToolCatalog.of(ToolSearchConfig.defaults(),
                analyticsToolProvider.deferredTools(), model.chatModel());
        ContextPolicy contextPolicy = residentTools.isEmpty() ? null : ContextPolicy.builder()
                .protectedTools(residentTools.stream()
                        .map(tool -> tool.getToolDefinition().name()).toArray(String[]::new))
                .build();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model.chatModel(), residentTools, 20)
                .taskManager(taskManager)
                .thinkingMode(model.thinkingMode())
                .persistenceHook(persistenceHook)
                .toolCatalog(catalog)
                .contextPolicy(contextPolicy)
                .build();
        analyticsExecutorsByModelId.put(resolvedId, executor);
        return executor;
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

        List<ToolCallback> webSearchTools = webSearchToolProvider.toolCallbacks();
        String effectiveModelId = resolveToolCallingModel(resolvedId, !webSearchTools.isEmpty());
        AgentLoopExecutor cached = webSearchExecutorsByModelId.get(effectiveModelId);
        if (cached != null) {
            return cached;
        }

        RegisteredModel model = modelsById.get(effectiveModelId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + effectiveModelId);
        }
        List<ToolCallback> tools = new ArrayList<>(baseTools);
        tools.addAll(webSearchTools);
        AgentLoopExecutor executor = buildExecutor(model, tools, null);
        // 只缓存"搜索工具真的挂上了"的结果——如果这次是降级（工具列表为空），
        // 不缓存，让下一次请求有机会在 Tavily 恢复后重新拿到一个真正带搜索的执行器
        if (!webSearchTools.isEmpty()) {
            webSearchExecutorsByModelId.put(effectiveModelId, executor);
        }
        return executor;
    }

    /**
     * 在 {@link #forModel(String, boolean)} 的基础上再叠加图表生成工具（issue #23）——图表生成
     * 不像联网搜索那样需要按对话开关（没有隐私/成本上的权衡要留给调用方决定），所以这里不额外暴露
     * 一个布尔开关参数，由 {@code AgentLoopController} 统一挂载；mcp-echarts 本次不可用时静默降级为
     * {@link #forModel(String, boolean)} 的结果（不带图表工具），绝不抛异常打断整个请求。
     *
     * <p>图表工具的输出（一段 URL）会被登记进这个执行器的 {@code ContextPolicy} 保护名单，
     * {@code ContextCompactor} 的常规压缩逻辑因此永远不会碰它——即便以后 auto_compact 真的触发，
     * 图表 URL 也会原样保留，不会被换成占位符。
     */
    public AgentLoopExecutor forModelWithCharts(String modelId, boolean webSearchEnabled) {
        if (chartToolProvider == null) {
            return forModel(modelId, webSearchEnabled);
        }
        List<ToolCallback> chartTools = chartToolProvider.toolCallbacks();
        if (chartTools.isEmpty()) {
            return forModel(modelId, webSearchEnabled);
        }

        String resolvedId = resolve(modelId);
        List<ToolCallback> webSearchTools = (webSearchEnabled && webSearchToolProvider != null)
                ? webSearchToolProvider.toolCallbacks() : List.of();
        List<ToolCallback> tools = new ArrayList<>(baseTools);
        tools.addAll(webSearchTools);
        tools.addAll(chartTools);
        String effectiveModelId = resolveToolCallingModel(resolvedId, !tools.isEmpty());
        String cacheKey = effectiveModelId + "|" + webSearchEnabled;
        AgentLoopExecutor cached = chartExecutorsByKey.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        RegisteredModel model = modelsById.get(effectiveModelId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + effectiveModelId);
        }

        ContextPolicy contextPolicy = ContextPolicy.builder()
                .protectedTools(chartTools.stream().map(tool -> tool.getToolDefinition().name()).toArray(String[]::new))
                .build();
        AgentLoopExecutor executor = buildExecutor(model, tools, contextPolicy);
        // 图表工具非空才缓存——理由和上面 webSearch 分支一致：一次降级不该锁死后续所有请求
        chartExecutorsByKey.put(cacheKey, executor);
        return executor;
    }

    /**
     * 给 DeepResearch 这类"内部会自己反复调用 ReAct 循环，但每次调用都不是用户发起的一轮对话"
     * 的编排逻辑用——不缓存（调用方通常只在装配阶段调一次并自己持有引用），不挂
     * {@link #persistenceHook}。如果复用 {@link #forModel} 系列返回的执行器，DeepResearch 内部
     * 的 critique/plan/summarize 每个子调用都会各自生成一个随机 conversationId 落进
     * {@code agent_session}，把面向用户的会话历史侧栏污染成一堆内部子提示词（曾经真实发生过，
     * 一次 DeepResearch 请求能在 {@code agent_session} 里留下十几条几千到上万字的垃圾行）。
     */
    public AgentLoopExecutor forInternalOrchestration(String modelId, boolean webSearchEnabled) {
        String resolvedId = resolve(modelId);
        List<ToolCallback> tools = (webSearchEnabled && webSearchToolProvider != null)
                ? webSearchToolProvider.toolCallbacks() : List.of();
        String effectiveModelId = resolveToolCallingModel(resolvedId, !tools.isEmpty());
        RegisteredModel model = modelsById.get(effectiveModelId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + effectiveModelId);
        }
        return buildExecutor(model, tools, null, false);
    }

    /**
     * 暴露某个模型标识对应的裸 {@link ChatModel}——目前唯一的调用方是 DeepResearch 自己的专用
     * 上下文压缩器（issue #37）：它压缩的是 critique/summarize 用到的检索结果文本，这份文本
     * 从来不经过 {@link AgentLoopExecutor} 的 ReAct 循环，所以需要绕开这一层拿到裸模型去发起
     * 摘要调用，跟 {@link #forModel} 系列返回"已经装配好的执行器"是两回事。
     */
    public ChatModel chatModelFor(String modelId) {
        String resolvedId = resolve(modelId);
        RegisteredModel model = modelsById.get(resolvedId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + resolvedId);
        }
        return model.chatModel();
    }

    private String resolve(String modelId) {
        return (modelId == null || modelId.isBlank()) ? defaultModelId : modelId;
    }

    /**
     * OpenAI 流式协议允许 tool call 后续增量分片省略 id，但当前 OpenAI 兼容客户端会对该 Optional
     * 直接调用 get。qwen-plus 走这条客户端路径时，工具仍需保留流式分片给 Runtime 自己重组，
     * 因此统一切到原生客户端已验证兼容的 deepseek-chat；不挂工具的执行器仍使用用户选择的 qwen-plus。
     */
    private String resolveToolCallingModel(String requestedModelId, boolean hasTools) {
        if (!hasTools || !QWEN_PLUS.equals(requestedModelId)) {
            return requestedModelId;
        }
        if (!modelsById.containsKey(TOOL_CALLING_COMPATIBLE_MODEL)) {
            throw new IllegalStateException("工具调用需要兼容模型，但未注册: " + TOOL_CALLING_COMPATIBLE_MODEL);
        }
        return TOOL_CALLING_COMPATIBLE_MODEL;
    }

    private static AgentLoopExecutor requireExecutor(Map<String, AgentLoopExecutor> executors, String modelId) {
        AgentLoopExecutor executor = executors.get(modelId);
        if (executor == null) {
            throw new IllegalArgumentException("未知的模型标识: " + modelId);
        }
        return executor;
    }
}
