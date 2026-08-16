package com.agenttrail.web.service;
import com.agenttrail.web.controller.AgentLoopController;

import com.agenttrail.capability.analytics.AnalyticsToolProvider;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.loop.hook.AgentHooks;
import com.agenttrail.loop.hook.SessionBudgetTracker;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.memory.MemoryStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.security.DataProvenancePolicy;
import com.agenttrail.loop.security.PiiMasker;
import com.agenttrail.loop.security.PromptInjectionGuard;
import com.agenttrail.loop.security.ToolRateLimiter;
import com.agenttrail.loop.skills.SkillManager;
import com.agenttrail.loop.trace.TraceStore;
import io.micrometer.core.instrument.MeterRegistry;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.FileContentTool;
import com.agenttrail.loop.tools.ViewImageTool;
import com.agenttrail.loop.tools.chart.ChartToolProvider;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.websearch.TavilySearchToolProvider;
import com.agenttrail.loop.profile.RuntimeModule;
import com.agenttrail.loop.profile.RuntimeProfile;
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

    private static final String QWEN_PLUS = com.agenttrail.platform.model.ToolCallingCompatibility.QWEN_PLUS;
    private static final String TOOL_CALLING_COMPATIBLE_MODEL =
            com.agenttrail.platform.model.ToolCallingCompatibility.FALLBACK_MODEL_ID;

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
    private final AgentHooks sharedHooks;
    private final PauseConfig pauseConfig;
    private final ToolRiskRegistry toolRiskRegistry;
    private final SessionBudgetTracker sessionBudgetTracker;
    private final TraceStore traceStore;
    private final MeterRegistry meterRegistry;
    /** 三者传 null 表示对应机制未启用，见 {@link AgentLoopExecutor.Builder} 的"null = 不启用"约定。 */
    private final PromptInjectionGuard promptInjectionGuard;
    private final PiiMasker piiMasker;
    private final ToolRateLimiter toolRateLimiter;
    /**
     * 传 null 表示这套装配完全不提供 Skill 工具——只接进"普通对话"这一路（{@link #buildExecutor}
     * 服务的 plain/webSearch/chart 三种变体），不接 {@link #forAnalytics}（DataAgent 明确"不复用
     * 文件/Shell 等其它工具"）也不接 {@link #forInternalOrchestration}（DeepResearch 内部子调用，
     * 不是用户直接发起的一轮对话）。
     */
    private final SkillManager skillManager;
    /**
     * 传 null 表示这套装配完全不提供分层记忆——只接普通对话执行器（{@link #buildExecutor}），
     * 不接 {@link #forAnalytics}/{@link #forInternalOrchestration}，接入范围和 {@link #skillManager}
     * 保持一致（DataAgent/DeepResearch 子调用都不是"这个用户的一次对话"语义）。
     */
    private final MemoryStore memoryStore;

    /**
     * One constructor seam; optional collaborators are selected by the named factory configuration.
     *
     * <p>这些 {@code Object...} 位置槽是待偿的技术债，不是目标形态——编译期不做任何类型检查，
     * 传错顺序只会在运行时炸成 {@code ClassCastException}。Phase 3 会连同 {@code AgentLoopExecutor}
     * 一起换成由 {@code AgentDefinition} 驱动的声明式装配；在那之前，
     * {@code AgentLoopExecutor.Builder} 是唯一类型安全的构造入口。
     */
    public AgentLoopExecutorFactory(List<RegisteredModel> models, String defaultModelId, Object... options) {
        AgentTaskManager taskManager = option(options, 0, AgentTaskManager.class, new AgentTaskManager());
        TavilySearchToolProvider webSearchToolProvider = option(options, 1, TavilySearchToolProvider.class, null);
        ChartToolProvider chartToolProvider = option(options, 2, ChartToolProvider.class, null);
        TurnPersistenceHook persistenceHook = option(options, 3, TurnPersistenceHook.class, null);
        FileContentTool fileContentTool = option(options, 4, FileContentTool.class, null);
        FileStore fileStore = option(options, 5, FileStore.class, null);
        AnalyticsToolProvider analyticsToolProvider = option(options, 6, AnalyticsToolProvider.class, null);
        PauseConfig pauseConfig = option(options, 7, PauseConfig.class, null);
        ToolRiskRegistry toolRiskRegistry = option(options, 8, ToolRiskRegistry.class, ToolRiskRegistry.defaults());
        SessionBudgetTracker sessionBudgetTracker = option(options, 9, SessionBudgetTracker.class, null);
        TraceStore traceStore = option(options, 10, TraceStore.class, null);
        MeterRegistry meterRegistry = option(options, 11, MeterRegistry.class, null);
        PromptInjectionGuard promptInjectionGuard = option(options, 12, PromptInjectionGuard.class, null);
        PiiMasker piiMasker = option(options, 13, PiiMasker.class, null);
        ToolRateLimiter toolRateLimiter = option(options, 14, ToolRateLimiter.class, null);
        SkillManager skillManager = option(options, 15, SkillManager.class, null);
        MemoryStore memoryStore = option(options, 16, MemoryStore.class, null);
        this.sharedHooks = new AgentHooks(List.of(),
                List.of(new com.agenttrail.loop.hook.ToolPolicyPreToolUseHook(toolRiskRegistry)),
                List.of(), List.of(), List.of(), List.of());
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
        this.pauseConfig = pauseConfig;
        this.toolRiskRegistry = toolRiskRegistry;
        this.sessionBudgetTracker = sessionBudgetTracker;
        this.traceStore = traceStore;
        this.meterRegistry = meterRegistry;
        this.promptInjectionGuard = promptInjectionGuard;
        this.piiMasker = piiMasker;
        this.toolRateLimiter = toolRateLimiter;
        this.skillManager = skillManager;
        this.memoryStore = memoryStore;
        ViewImageTool viewImageTool = option(options, 17, ViewImageTool.class, null);
        // 文件读取和看图都属于基线层：无条件挂载、不按开关、不换执行器（requirements §7.2）
        List<ToolCallback> assembledBaseTools = new ArrayList<>();
        if (fileContentTool != null) {
            assembledBaseTools.add(fileContentTool.toolCallback());
        }
        if (viewImageTool != null) {
            assembledBaseTools.add(viewImageTool.toolCallback());
        }
        this.baseTools = List.copyOf(assembledBaseTools);
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

    private static <T> T option(Object[] options, int index, Class<T> type, T fallback) {
        if (index >= options.length || options[index] == null) {
            return fallback;
        }
        return type.cast(options[index]);
    }

    /**
     * @param catalog ToolSearch 延迟工具池。**当前所有生产装配都传 null**——issue #95 之后
     *                DataAgent 的六个工具改为常驻，ToolSearch 就不在任何生产路径上了。机制
     *                本身按 `requirements.md` §9 的决定保留（"实现了它、又用评测证明了它在这个
     *                场景不适用"本身是完整的工程判断），参数留着，等真有工具多到需要它的场景。
     */
    private RuntimeProfile runtimeProfile(ContextPolicy contextPolicy, ToolCatalog catalog) {
        return new RuntimeProfile(
                RuntimeModule.contextCompaction(contextPolicy == null ? ContextPolicy.defaults() : contextPolicy),
                RuntimeModule.memory(memoryStore),
                RuntimeModule.pauseResume(pauseConfig),
                RuntimeModule.trace(traceStore),
                RuntimeModule.stageOutput(com.agenttrail.loop.stageoutput.StageOutputManager.EMPTY),
                RuntimeModule.toolSearch(catalog));
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
        return buildExecutor(model, tools, contextPolicy, persist, DataProvenancePolicy.DISABLED);
    }

    /**
     * 认可的数据产出工具：SQL 查询结果、上传文件内容。{@code calculate} 不算——它是纯函数求值，
     * 喂给它的数字本身可能就是编的，把它当来源等于给伪造开一个后门。
     */
    private static DataProvenancePolicy chartProvenancePolicy(List<ToolCallback> chartTools) {
        if (chartTools.isEmpty()) {
            return DataProvenancePolicy.DISABLED;
        }
        return new DataProvenancePolicy(
                chartTools.stream().map(tool -> tool.getToolDefinition().name()).collect(Collectors.toSet()),
                java.util.Set.of("execute_sql", "load_file_content"));
    }

    private AgentLoopExecutor buildExecutor(RegisteredModel model, List<ToolCallback> tools, ContextPolicy contextPolicy,
            boolean persist, DataProvenancePolicy dataProvenancePolicy) {
        AgentLoopExecutor.Builder builder = AgentLoopExecutor.builder(model.chatModel(), tools, 10)
                .taskManager(taskManager)
                .thinkingMode(model.thinkingMode())
                .persistenceHook(persist ? persistenceHook : null)
                .fileStore(fileStore)
                .hooks(sharedHooks)
                .pauseConfig(pauseConfig)
                .budgetTracker(sessionBudgetTracker)
                .traceStore(traceStore)
                .meterRegistry(meterRegistry)
                .modelName(model.id())
                .promptInjectionGuard(promptInjectionGuard)
                .piiMasker(piiMasker)
                .toolRateLimiter(toolRateLimiter)
                .skillManager(skillManager)
                .memoryStore(memoryStore)
                .dataProvenancePolicy(dataProvenancePolicy)
                .runtimeProfile(runtimeProfile(contextPolicy, null));
        if (contextPolicy != null) {
            builder.contextPolicy(contextPolicy);
        }
        return builder.build();
    }

    /** @param modelId 为 null 或空串时使用默认模型；未注册的标识直接抛异常，不做静默兜底 */
    public AgentLoopExecutor forModel(String modelId) {
        return forModel(modelId, false);
    }

    /**
     * DataAgent 专用执行器：只挂载分析白名单和图表工具，不复用文件/Shell 工具。
     *
     * <p>和普通对话执行器的三处实质差别：六个分析工具常驻（不走 ToolSearch）、轮次上限 20
     * 而不是 10、同一工具连续失败 3 次提前止损。{@code skillManager} 两边都接，DataAgent 的
     * SOP 走和普通对话一样的 {@code Skill} 元工具通道。
     */
    public AgentLoopExecutor forAnalytics(String modelId) {
        if (analyticsToolProvider == null) {
            throw new IllegalStateException("分析能力未启用，请联系管理员配置分析数据源");
        }
        String resolvedId = resolve(modelId);
        AgentLoopExecutor cached = analyticsExecutorsByModelId.get(resolvedId);
        if (cached != null) {
            return cached;
        }
        List<ToolCallback> chartTools = chartToolProvider == null
                ? List.of() : chartToolProvider.toolCallbacks();
        // 六个分析工具全部常驻（issue #95）。它们曾经全在 ToolSearch 的延迟池里，模型第一轮
        // 只看得到 search_tools，而"搜到"和"能调用"之间还隔一轮——2026-08-05 的评测实测里，
        // 多轮失败案例的 toolCalls 只有 search_tools，模型回答"没找到能查询数据的工具"。
        // 根因不是打分算法误伤：真实中文查询词对这批工具的关键词得分全是 0，100% 落进 LLM
        // 语义兜底，而那次兜底调用本身不稳定（踩坑点 #85）。延迟发现是为"工具多到撑爆上下文"
        // 设计的机制，六个工具用它是错配，不是调参能解决的问题。
        List<ToolCallback> residentTools = new ArrayList<>(analyticsToolProvider.tools());
        residentTools.addAll(chartTools);
        String effectiveId = resolveToolCallingModel(resolvedId, true);
        RegisteredModel model = modelsById.get(effectiveId);
        if (model == null) {
            throw new IllegalArgumentException("未知的模型标识: " + effectiveId);
        }
        // 图表工具的产出是一段 URL，被压掉就再也拿不回来；分析工具的结果是可以重查的，
        // 不进保护名单。Skill 正文由 ContextPolicy 的内置名单按工具名保护，不用在这里列。
        ContextPolicy contextPolicy = chartTools.isEmpty() ? null : ContextPolicy.builder()
                .protectedTools(chartTools.stream()
                        .map(tool -> tool.getToolDefinition().name()).toArray(String[]::new))
                .build();
        AgentLoopExecutor executor = AgentLoopExecutor.builder(model.chatModel(), residentTools, 20)
                .taskManager(taskManager)
                .thinkingMode(model.thinkingMode())
                .persistenceHook(persistenceHook)
                // DataAgent 的 SOP 是 skills/data-analysis/SKILL.md（issue #95）。之前这条链上
                // 没有 skillManager，分析执行器的系统提示词实际只有日期区块——模型既不知道
                // 自己是个数据分析 Agent，也不知道该按什么顺序调工具。
                .skillManager(skillManager)
                .contextPolicy(contextPolicy)
                .hooks(sharedHooks)
                .pauseConfig(pauseConfig)
                .budgetTracker(sessionBudgetTracker)
                .traceStore(traceStore)
                .meterRegistry(meterRegistry)
                .modelName(model.id())
                .promptInjectionGuard(promptInjectionGuard)
                .piiMasker(piiMasker)
                .toolRateLimiter(toolRateLimiter)
                // ReAct+Skill 的自我修正没有 DataAgent 那种 Gate+maxRetries 的图结构上限，SKILL.md
                // 写的重试预算只是给模型的指导，不是强制——同一个工具连续失败 3 次就提前止损，
                // 不再指望它在 maxRounds=20 撞顶之前自己收敛。
                .maxConsecutiveToolFailures(3)
                .dataProvenancePolicy(chartProvenancePolicy(chartTools))
                .runtimeProfile(runtimeProfile(contextPolicy, null))
                .build();
        // 只缓存"图表工具真的挂上了"的结果，和下面 forModel(webSearchEnabled) 是同一条规则：
        // mcp-echarts 本次连不上时构造出来的是个没有图表工具的降级执行器，把它缓存下来会让
        // mcp-echarts 恢复之后的所有分析会话继续用这个残缺执行器，直到应用重启为止——
        // 真实踩过：mcp-echarts 起不来期间点了一次"数据分析"，之后修好了服务，新会话里
        // 依然一个绘图工具都没有。chartToolProvider 为 null 是"压根没配图表能力"，
        // 那是稳定状态，正常缓存，否则会变成每次请求都重建执行器。
        // 判据用 chartTools 而不是 residentTools——后者现在恒非空（六个分析工具常驻），
        // 拿它判断等于把降级结果也缓存下来，那正是这段注释要防的事。
        if (chartToolProvider == null || !chartTools.isEmpty()) {
            analyticsExecutorsByModelId.put(resolvedId, executor);
        }
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
        AgentLoopExecutor executor = buildExecutor(model, tools, contextPolicy, true, chartProvenancePolicy(chartTools));
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
        if (!com.agenttrail.platform.model.ToolCallingCompatibility.needsFallback(requestedModelId, hasTools)) {
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
