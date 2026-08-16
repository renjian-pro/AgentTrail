package com.agenttrail.loop.core;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.context.MessageRendering;
import com.agenttrail.capability.file.FilePromptFormatter;
import com.agenttrail.capability.file.FileStore;
import com.agenttrail.loop.hook.AgentHooks;
import com.agenttrail.loop.hook.HookContext;
import com.agenttrail.loop.hook.SessionBudgetTracker;
import com.agenttrail.loop.hook.ToolInvocation;
import com.agenttrail.loop.memory.MemoryExtractor;
import com.agenttrail.loop.memory.MemoryPromptFormatter;
import com.agenttrail.loop.memory.MemoryStore;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.runtime.api.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseReason;
import com.agenttrail.loop.pause.PauseState;
import com.agenttrail.loop.pause.PendingToolCall;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.pause.SafePoint;
import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.persistence.TurnRecord;
import com.agenttrail.loop.security.PiiMasker;
import com.agenttrail.loop.security.PromptInjectionGuard;
import com.agenttrail.loop.security.ToolRateLimiter;
import com.agenttrail.loop.skills.SkillManager;
import com.agenttrail.loop.stageoutput.StageContext;
import com.agenttrail.loop.stageoutput.StageOutputManager;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.trace.TraceRecord;
import com.agenttrail.loop.trace.TraceStore;
import com.agenttrail.loop.structured.JsonRepair;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.search.ToolSearchSession;
import com.agenttrail.loop.profile.RuntimeProfile;
import com.agenttrail.loop.profile.RuntimeProfileValidator;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.platform.ids.RunId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手写 ReAct 循环：一轮 = 一次流式模型调用 +（如果模型要调工具）一批并行工具执行 + 递归进入下一轮。
 *
 * <p>终止条件只有一个——某一轮从头到尾没有出现工具调用，那一轮的文本就是最终答案。
 * 不依赖模型自报"我说完了"这类约定，因为那属于"提示词当契约"，模型不遵守时无法兜底。
 *
 * <p>工具执行权完全在本类手里：模型调用直接打到 {@link ChatModel#stream}，不经过
 * {@code ChatClient}/Advisor 链（见 ADR-0002），因此框架层的自动工具执行天然不会发生。
 *
 * <p>各职责由独立协作者承担，本类只负责编排：
 * {@link LlmInvoker} 发请求、{@link RoundState} 攒本轮响应、{@link ToolCallExecutor} 执行工具、
 * {@link ContextCompactor} 控上下文体积、{@link AgentTaskManager} 管任务生命周期。
 */
public class AgentLoopExecutor {

    /** 新一轮开始前预加载的历史上限——和单轮上下文压缩阈值是两码事，故意不复用同一个常量。 */
    private static final int HISTORY_TOKEN_BUDGET = 8_000;

    private static final ZoneId APP_ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 一整轮（含可能的工具调用）的绝对时钟上限——踩坑点 #93：{@link LlmInvoker} 的 TTFT/idle 超时
     * 按"距上一个信号多久"计时，2026-08-06 用一次真实 DashScope 卡顿复现过它会被无限重置——模型
     * 文字已经吐完，但连接持续零星发一些不产出可见内容的收尾帧（对 Reactor 而言仍是"收到了新信号"），
     * 30 秒 idle 超时因此从未触发，单飞锁永久卡住。这道兜底不看"有没有信号"，只看这一轮从订阅开始
     * 算的绝对时长——不管卡在等首字节、卡在逐 chunk 间隔，还是卡在"有信号但从不真正完成"，统一在
     * 这个时间点强制收尾。8 分钟给足了正常场景的余量（{@link ToolCallExecutor} 自己的工具轮次上限
     * 已经是 5 分钟，这里是它之上、覆盖整轮（含 LLM 流式阶段）的最后一道防线）。
     */
    static final Duration DEFAULT_ROUND_TIMEOUT = Duration.ofMinutes(8);

    private final LlmInvoker llmInvoker;
    private final ToolCallExecutor toolCallExecutor;
    private final AgentTaskManager taskManager;
    private final ContextCompactor contextCompactor;
    private final ThinkingModeProcessor thinkingModeProcessor;
    private final TurnPersistenceHook persistenceHook;
    private final List<ToolCallback> tools;
    private final ToolCatalog toolCatalog;
    private final PauseConfig pauseConfig;
    private final StageOutputManager stageOutputManager;
    private final TraceStore traceStore;
    private final MemoryStore memoryStore;
    private final MemoryExtractor memoryExtractor;
    private final FileStore fileStore;
    private final int maxRounds;
    private final int maxConsecutiveToolFailures;
    private final AgentHooks hooks;
    private final SessionBudgetTracker budgetTracker;
    private final MeterRegistry meterRegistry;
    private final String modelName;
    private final PromptInjectionGuard promptInjectionGuard;
    private final PiiMasker piiMasker;
    private final ToolRateLimiter toolRateLimiter;
    private final Duration roundTimeout;
    /**
     * 传 null 表示这套装配完全不提供 Skill 工具，行为与没有这个机制时一致。非 null 时，
     * 第一次调度这一轮时通过 {@link SkillManager#buildSkillsTool()} 装配，随后
     * 和 {@link #toolCatalog} 驱动的 {@link com.agenttrail.loop.tools.search.ToolSearchSession}
     * 共用同一个"这次对话请求专属工具"的解析槽位（见 {@link #finishRound}）。
     *
     * <p>两者不会撞车：issue #95 之后 {@code toolCatalog} **在所有生产装配里都是 null**
     * （DataAgent 的六个工具改成了常驻），而 {@code skillManager} 在普通对话和分析执行器上都接。
     * 槽位的二选一逻辑保留着，因为 {@code toolCatalog} 仍是 Builder 上的合法选项，只是暂时没人用。
     */
    private final SkillManager skillManager;
    private final RuntimeProfile runtimeProfile;
    private final ContextAssembler contextAssembler;
    private final RoundDriver roundDriver;
    private final RunCompletionCoordinator<RunContext> completionCoordinator;
    private final RunLifecycleManager lifecycleManager;
    private final ToolRoundExecutor toolRoundExecutor;

    /**
     * Single construction seam. Optional collaborators are supplied in positional slots by the
     * builder and older integrations remain source-compatible without a telescoping constructor chain.
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds, Object... options) {
        AgentTaskManager taskManager = option(options, 0, AgentTaskManager.class, new AgentTaskManager());
        ContextPolicy contextPolicy = option(options, 1, ContextPolicy.class, null);
        ThinkingMode thinkingMode = option(options, 2, ThinkingMode.class, ThinkingMode.DISABLED);
        TurnPersistenceHook persistenceHook = option(options, 3, TurnPersistenceHook.class, null);
        ToolCatalog toolCatalog = option(options, 4, ToolCatalog.class, null);
        PauseConfig pauseConfig = option(options, 5, PauseConfig.class, null);
        StageOutputManager stageOutputManager = option(options, 6, StageOutputManager.class, null);
        TraceStore traceStore = option(options, 7, TraceStore.class, null);
        MemoryStore memoryStore = option(options, 8, MemoryStore.class, null);
        FileStore fileStore = option(options, 9, FileStore.class, null);
        int maxConsecutiveToolFailures = numberOption(options, 10, 0);
        AgentHooks hooks = option(options, 11, AgentHooks.class, AgentHooks.EMPTY);
        SessionBudgetTracker budgetTracker = option(options, 12, SessionBudgetTracker.class, null);
        MeterRegistry meterRegistry = option(options, 13, MeterRegistry.class, null);
        String modelName = option(options, 14, String.class, "unknown");
        PromptInjectionGuard promptInjectionGuard = option(options, 15, PromptInjectionGuard.class, null);
        PiiMasker piiMasker = option(options, 16, PiiMasker.class, null);
        ToolRateLimiter toolRateLimiter = option(options, 17, ToolRateLimiter.class, null);
        Duration roundTimeout = option(options, 18, Duration.class, DEFAULT_ROUND_TIMEOUT);
        SkillManager skillManager = option(options, 19, SkillManager.class, null);
        RuntimeProfile runtimeProfile = option(options, 20, RuntimeProfile.class, RuntimeProfile.defaults());
        RuntimeProfileValidator.validate(runtimeProfile);

        List<ToolCallback> baseTools = tools == null ? List.of() : tools;
        List<ToolCallback> allTools = withDeferredPool(baseTools, toolCatalog);
        this.llmInvoker = new LlmInvoker(chatModel, allTools);
        this.contextAssembler = new ContextAssembler();
        this.roundDriver = new RoundDriver((request, callbacks) -> llmInvoker.streamModelRound(request,
                callbacks.stream().map(ToolCallback.class::cast).toList()));
        this.completionCoordinator = new RunCompletionCoordinator<>(ignored -> { });
        this.lifecycleManager = new RunLifecycleManager(taskManager);
        this.toolRoundExecutor = new ToolRoundExecutor();
        this.toolCallExecutor = new ToolCallExecutor(allTools, meterRegistry);
        this.taskManager = taskManager;
        this.contextCompactor = (contextPolicy == null) ? null : new ContextCompactor(contextPolicy, chatModel);
        this.thinkingModeProcessor = new ThinkingModeProcessor(thinkingMode);
        this.persistenceHook = persistenceHook;
        this.tools = baseTools;
        this.toolCatalog = toolCatalog;
        this.pauseConfig = pauseConfig;
        this.stageOutputManager = (stageOutputManager == null) ? StageOutputManager.EMPTY : stageOutputManager;
        this.traceStore = traceStore;
        this.memoryStore = memoryStore;
        this.memoryExtractor = (memoryStore == null) ? null : new MemoryExtractor(chatModel, memoryStore);
        this.fileStore = fileStore;
        this.maxRounds = maxRounds;
        this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
        this.hooks = hooks == null ? AgentHooks.EMPTY : hooks;
        this.budgetTracker = budgetTracker;
        this.meterRegistry = meterRegistry;
        this.modelName = (modelName == null || modelName.isBlank()) ? "unknown" : modelName;
        this.promptInjectionGuard = promptInjectionGuard;
        this.piiMasker = piiMasker;
        this.toolRateLimiter = toolRateLimiter;
        this.roundTimeout = (roundTimeout == null) ? DEFAULT_ROUND_TIMEOUT : roundTimeout;
        this.skillManager = skillManager;
        this.runtimeProfile = runtimeProfile;
    }

    private static int numberOption(Object[] options, int index, int fallback) {
        return index < options.length && options[index] instanceof Number number ? number.intValue() : fallback;
    }

    private static <T> T option(Object[] options, int index, Class<T> type, T fallback) {
        if (index >= options.length || options[index] == null) {
            return fallback;
        }
        return type.cast(options[index]);
    }

    /**
     * @param taskManager     任务管理器由外部传入并**共享**——停止接口要能找到正在跑的任务，
     *                        每次请求各自 new 一个的话，停止请求永远找不到目标
     * @param contextPolicy   上下文压缩策略；传 null 表示不压缩，循环行为与没有该机制时一致
     * @param thinkingMode    当前所用模型交付思考过程的方式
     * @param persistenceHook 会话持久化回调；传 null 表示不落库、不预加载历史（如子 Agent）
     */

    /**
     * @param toolCatalog ToolSearch 延迟工具池；传 null 表示不启用该机制，行为与没有它时完全一致。
     *                    池子里的工具**始终**可以被执行层解析到（{@link ToolCallExecutor} 按全量池建表），
     *                    但只有本次对话已经"搜到"的那部分才会被下一轮的工具清单暴露给模型——
     *                    可见性限制只在喂给 LLM 那一侧，执行层不做二次过滤
     */

    /**
     * @param pauseConfig 暂停/恢复机制（issue #13）；传 null 表示完全不启用，行为与没有它时一致。
     *                    命中 {@code pauseConfig} 审批名单的工具调用不会被执行，循环改为落一份
     *                    {@link PauseState} 快照、发 {@link AgentStreamEvent.Paused} 事件后结束，
     *                    之后只能通过 {@link #resume} 恢复，而不是靠再调一次 {@link #stream}
     */

    /**
     * @param stageOutputManager 分阶段输出机制（issue #16）；传 null 等价于 {@link StageOutputManager#EMPTY}，
     *                           三个生命周期钩子都是空操作，行为和没有这个机制时完全一致
     */

    /**
     * @param traceStore 追踪审计存储（issue #17）；传 null 表示不启用——不记录任何一轮，
     *                   也不做消息渲染这类额外开销，行为与没有这个机制时完全一致。这个参数本身
     *                   就是"显式开关"：要启用就必须主动传一个实现进来
     */

    /**
     * @param memoryStore 分层记忆体系的中间层存储（issue #19：画像/偏好/指令/事实）；传 null 表示
     *                    不启用——既不在 {@link #stream} 里读取注入，也不在收尾时提取，行为与没有
     *                    这个机制时完全一致。短期历史层由 {@code persistenceHook} 负责；跨会话语义
     *                    摘要层依赖向量库，留给 Phase 4，不是这个参数管的范围
     */

    /**
     * @param fileStore 文件问答的元数据存储（issue #28）；传 null 表示不启用——既不在
     *                  {@link #stream} 里注入"会话文件"区块，也不在收尾时回填 turnId，
     *                  行为与没有这个机制时完全一致。文件的解析/向量化/多模态识别由
     *                  {@code FileQaService} 编排（issue #21/#26/#27），本类只负责
     *                  "让模型看见这一轮和历史上传了哪些文件"+"轮次结束后回填归属"
     */

    /**
     * @param maxConsecutiveToolFailures 同一个工具名在这次推理里连续失败达到这个次数就提前终止本轮
     *                                   推理，把已知的失败原因如实告诉用户，而不是继续把预算烧在
     *                                   "生成→报错→再生成→再报错"的自我修正循环上；{@code <= 0}
     *                                   表示不启用，行为和没有这个机制时完全一致——{@code maxRounds}
     *                                   仍然是唯一的硬顶，但那是"整轮最多转几圈"的粗粒度上限，
     *                                   不区分"这几圈是不是在原地打转"
     */




    /**
     * @param promptInjectionGuard 提示词注入检测；传 null 表示不启用，行为与没有这个机制时一致
     * @param piiMasker            用户输入 PII 打码；传 null 表示不启用——原始文本原样进入
     *                             {@code messages} 和落库记录
     * @param toolRateLimiter      单会话工具调用限速；传 null 表示不启用，所有工具调用都放行，
     *                             行为与没有这个机制时一致
     */

    /** 测试专用：注入一个短得多的 {@code roundTimeout}，不用真的等 8 分钟才能验证超时降级。 */

    /** 真正的规范构造函数：{@link #skillManager} 是最后加入的可选机制，只通过 {@link Builder} 设置。 */
    /**
     * 起一个 builder：{@code chatModel}/{@code tools}/{@code maxRounds} 是唯一必填项，其余可选机制
     * 通过命名方法设置。比起继续在telescoping 构造函数链上叠新重载——每加一个可选机制就多一个
     * 只为它而生的重载，调用方为了设最后一个参数得排一串 {@code null} 占位——这里改用命名方法，
     * 加错顺序或漏传一个不会被编译器悄悄放过。不影响、不替换现有构造函数，两种装配方式并存。
     */
    public static Builder builder(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        return new Builder(chatModel, tools, maxRounds);
    }

    public RuntimeProfile runtimeProfile() {
        return runtimeProfile;
    }

    public boolean cancel(String conversationId, CancellationReason reason) {
        return lifecycleManager.cancel(RunId.of(conversationId), reason);
    }

    public static final class Builder {

        private final ChatModel chatModel;
        private final List<ToolCallback> tools;
        private final int maxRounds;
        private AgentTaskManager taskManager = new AgentTaskManager();
        private ContextPolicy contextPolicy;
        private ThinkingMode thinkingMode = ThinkingMode.DISABLED;
        private TurnPersistenceHook persistenceHook;
        private ToolCatalog toolCatalog;
        private PauseConfig pauseConfig;
        private StageOutputManager stageOutputManager;
        private TraceStore traceStore;
        private MemoryStore memoryStore;
        private FileStore fileStore;
        private int maxConsecutiveToolFailures;
        private AgentHooks hooks = AgentHooks.EMPTY;
        private SessionBudgetTracker budgetTracker;
        private MeterRegistry meterRegistry;
        private String modelName = "unknown";
        private PromptInjectionGuard promptInjectionGuard;
        private PiiMasker piiMasker;
        private ToolRateLimiter toolRateLimiter;
        private SkillManager skillManager;
        private RuntimeProfile runtimeProfile = RuntimeProfile.defaults();

        private Builder(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
            this.chatModel = chatModel;
            this.tools = tools;
            this.maxRounds = maxRounds;
        }

        public Builder taskManager(AgentTaskManager taskManager) {
            this.taskManager = taskManager;
            return this;
        }

        public Builder contextPolicy(ContextPolicy contextPolicy) {
            this.contextPolicy = contextPolicy;
            return this;
        }

        public Builder thinkingMode(ThinkingMode thinkingMode) {
            this.thinkingMode = thinkingMode;
            return this;
        }

        public Builder persistenceHook(TurnPersistenceHook persistenceHook) {
            this.persistenceHook = persistenceHook;
            return this;
        }

        public Builder toolCatalog(ToolCatalog toolCatalog) {
            this.toolCatalog = toolCatalog;
            return this;
        }

        public Builder pauseConfig(PauseConfig pauseConfig) {
            this.pauseConfig = pauseConfig;
            return this;
        }

        public Builder stageOutputManager(StageOutputManager stageOutputManager) {
            this.stageOutputManager = stageOutputManager;
            return this;
        }

        public Builder traceStore(TraceStore traceStore) {
            this.traceStore = traceStore;
            return this;
        }

        public Builder memoryStore(MemoryStore memoryStore) {
            this.memoryStore = memoryStore;
            return this;
        }

        public Builder fileStore(FileStore fileStore) {
            this.fileStore = fileStore;
            return this;
        }

        /** @see AgentLoopExecutor#AgentLoopExecutor(ChatModel, List, int, AgentTaskManager, ContextPolicy,
         *      ThinkingMode, TurnPersistenceHook, ToolCatalog, PauseConfig, StageOutputManager, TraceStore,
         *      MemoryStore, FileStore, int) */
        public Builder maxConsecutiveToolFailures(int maxConsecutiveToolFailures) {
            this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
            return this;
        }

        public Builder hooks(AgentHooks hooks) {
            this.hooks = hooks;
            return this;
        }

        public Builder budgetTracker(SessionBudgetTracker budgetTracker) {
            this.budgetTracker = budgetTracker;
            return this;
        }

        public Builder meterRegistry(MeterRegistry meterRegistry) {
            this.meterRegistry = meterRegistry;
            return this;
        }

        public Builder modelName(String modelName) {
            this.modelName = modelName;
            return this;
        }

        public Builder promptInjectionGuard(PromptInjectionGuard promptInjectionGuard) {
            this.promptInjectionGuard = promptInjectionGuard;
            return this;
        }

        public Builder piiMasker(PiiMasker piiMasker) {
            this.piiMasker = piiMasker;
            return this;
        }

        public Builder toolRateLimiter(ToolRateLimiter toolRateLimiter) {
            this.toolRateLimiter = toolRateLimiter;
            return this;
        }

        public Builder skillManager(SkillManager skillManager) {
            this.skillManager = skillManager;
            return this;
        }

        public Builder runtimeProfile(RuntimeProfile runtimeProfile) {
            this.runtimeProfile = runtimeProfile;
            return this;
        }

        public AgentLoopExecutor build() {
            return new AgentLoopExecutor(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode,
                    persistenceHook, toolCatalog, pauseConfig, stageOutputManager, traceStore, memoryStore,
                    fileStore, maxConsecutiveToolFailures, hooks, budgetTracker, meterRegistry, modelName,
                    promptInjectionGuard, piiMasker, toolRateLimiter, DEFAULT_ROUND_TIMEOUT, skillManager,
                    runtimeProfile);
        }
    }

    private static List<ToolCallback> withDeferredPool(List<ToolCallback> tools, ToolCatalog toolCatalog) {
        if (toolCatalog == null) {
            return tools;
        }
        List<ToolCallback> merged = new ArrayList<>(tools);
        merged.addAll(toolCatalog.allTools());
        return merged;
    }

    /**
     * 发起一次完整的多轮推理，事件以流的形式实时推给调用方。
     *
     * <p>同一会话已有任务在跑时直接拒绝，返回一条错误事件而不是抛异常——
     * 调用方拿到的始终是一个正常结束的事件流，不需要为"并发冲突"单独写一套错误处理。
     *
     * @param question 用户本轮提问
     * @param params   运行时参数（会话 id、用户 id、系统级工具参数）
     */
    public Flux<AgentStreamEvent> stream(String question, RunnableParams params) {
        Sinks.Many<AgentStreamEvent> sink = EventSinks.bounded();

        if (!taskManager.registerTask(params.conversationId(), sink)) {
            EventSinks.emit(sink, new AgentStreamEvent.Error("CONCURRENT_EXECUTION", "该会话正在执行中，请稍后再试"));
            sink.tryEmitComplete();
            return sink.asFlux();
        }

        if (promptInjectionGuard != null && promptInjectionGuard.looksLikeInjection(question)) {
            taskManager.removeTask(params.conversationId());
            EventSinks.emit(sink, new AgentStreamEvent.Error(
                    "PROMPT_INJECTION_DETECTED", "检测到疑似的提示词注入攻击，本次请求已被拒绝"));
            sink.tryEmitComplete();
            return sink.asFlux();
        }
        // 打码后的文本才继续往下走——原始文本不落库、不进 messages（见 CLASSIFIER/落库落点）
        String sanitizedQuestion = (piiMasker == null) ? question : piiMasker.mask(question);

        List<Message> messages = new ArrayList<>();
        // 模型没有别的途径知道"今天"是哪天——不注入的话，"今天几号"这类问题会被当成
        // 需要工具才能回答的问题（曾经因此去猜一个不存在的 file_id 调用 load_file_content），
        // 或者更糟：编一个听起来合理但完全瞎猜的日期，还在同一句话里自称"无法获取"。
        messages.add(new SystemMessage(buildDateSection()));
        String memorySection = buildMemorySection(params.userId());
        if (!memorySection.isEmpty()) {
            // 放在最前面，定位是"背景信息"而不是这一轮问答本身——不跟 UserMessage 混在一起，
            // 也不需要为这一个机制单独引入"系统提示词构建器"（issue #18 的格式指令就没这么做，
            // 那是追加在问题后面；这里的内容性质不同，适合放在历史最前）
            messages.add(new SystemMessage(memorySection));
        }
        String fileSection = buildFileSection(params.conversationId());
        if (!fileSection.isEmpty()) {
            // 和记忆区块一样是独立的一条 SystemMessage，不是同一段文本里拼接——两者关注点不同，
            // 分开便于各自独立开关、独立测试断言
            messages.add(new SystemMessage(fileSection));
        }
        if (persistenceHook != null) {
            messages.addAll(persistenceHook.loadHistory(params.conversationId(), HISTORY_TOKEN_BUDGET));
        }
        messages = new ArrayList<>(contextAssembler.assemble(null, messages,
                withFormatInstruction(sanitizedQuestion, params.outputType())));

        // 每次对话请求各自开一个全新会话——发现的工具互相隔离，不会泄漏给并发的其他会话
        ToolSearchSession toolSearchSession = (toolCatalog == null) ? null : toolCatalog.newSession();

        // 工具执行会跳到独立调度器的线程上（见 ToolCallExecutor），MDC 这种 ThreadLocal 状态
        // 过了线程边界就读不到了；这里在还没跳线程之前，把发起这次请求的线程的 MDC 拍下来
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        RunContext context = new RunContext(sanitizedQuestion, params, messages, sink, new AtomicInteger(0),
                System.currentTimeMillis(), toolSearchSession, mdcSnapshot);
        fireSessionStart(context);
        context.emit(new AgentStreamEvent.AgentStart(params.conversationId()));
        stageOutputManager.afterStart(new StageContext(question, null, params), context::emit);
        scheduleRound(context);
        return sink.asFlux();
    }

    /**
     * 同步调用：语义上等价于把 {@link #stream} 阻塞收集成一次性结果，**直接复用**同一个公开的
     * {@link #stream} 入口，不是另起一套 loop 实现——单飞注册、工具执行、上下文压缩这些机制
     * 一个都不用重新接一遍（issue #15；参考实现的 {@code callViaStreamForResult} 走的是同一个
     * "阻塞收集 Text 事件"思路，只是它内部绕开了自己的公开 {@code stream} 入口直接摸轮次调度，
     * 因而要在 {@code call}/{@code stream} 两处各自重复一遍单飞注册——AgentTrail 的
     * {@link #stream} 本身就是自给自足的公开入口，直接包一层阻塞收集即可，不必重复）。
     *
     * <p>工具调用轮产生的正文是"模型的中间思考"，不是最终答案——每次看到 {@link AgentStreamEvent.ToolStart}
     * 就把已经攒下的文本清空，保证最终只剩最后一轮（无工具调用的收尾轮）的正文。
     *
     * <p>不支持中途暂停：{@link PauseConfig} 触发的暂停在同步调用里没有"之后再恢复"的自然落点——
     * 调用方已经在等一个返回值，不是攥着一个可以晚点再消费的 {@code Flux}，遇到就直接抛异常，
     * 让调用方改用 {@link #stream} + {@link #resume}。
     *
     * @return 这一轮的最终正文
     * @throws AgentCallException 模型调用失败（{@code code} 和对应的 {@link AgentStreamEvent.Error#code()}
     *                            一致），或者这一轮触发了暂停（{@code code} 是 {@code "PAUSED"}）
     */
    public String call(String question, RunnableParams params) {
        StringBuilder answer = new StringBuilder();
        AtomicReference<AgentCallException> failure = new AtomicReference<>();

        stream(question, params)
                .doOnNext(event -> {
                    switch (event) {
                        case AgentStreamEvent.Text text -> answer.append(text.content());
                        case AgentStreamEvent.ToolStart ignored -> answer.setLength(0);
                        case AgentStreamEvent.Error error -> failure.set(
                                new AgentCallException(error.code(), error.message()));
                        case AgentStreamEvent.Paused paused -> failure.set(new AgentCallException("PAUSED",
                                "同步调用触发了暂停（原因：" + paused.reason() + "），请改用 stream() + resume()"));
                        default -> {
                        }
                    }
                })
                .blockLast();

        if (failure.get() != null) {
            throw failure.get();
        }
        // 结构化输出场景下，流式返回的原始文本可能不是合法 JSON——这里做最后一次修复兜底，
        // 而不是在 stream() 里改：那边的 Text 事件已经边生成边推给调用方了，没法事后再改一遍
        return (params.outputType() == null) ? answer.toString() : JsonRepair.fixJson(answer.toString());
    }

    /**
     * 调度一轮：发起流式请求，边收边攒，流结束后再决定“收尾”还是“执行工具并进入下一轮”。
     *
     * <p>之所以要等整个流结束才决策，是因为一轮的性质（文本轮 / 工具调用轮）在流跑完之前
     * 是不确定的，详见 {@link RoundState}。
     */
    private void scheduleRound(RunContext context) {
        // 超出轮次预算后，本轮改成不挂任何工具——模型看不见工具，就没法再发起调用
        boolean toolsExhausted = maxRounds > 0 && context.nextRound() > maxRounds;
        // 一次对话复用同一份技能快照；SkillManager 在对话开始时装配，避免多轮中途改变工具语义
        ToolCallback skillTool = resolveSkillTool(context);
        List<ToolCallback> roundTools = toolsExhausted
                ? List.of() : withDiscoveredTools(context.toolSearchSession(), skillTool);

        // 压缩放在发请求之前：此时上一轮的工具结果刚落进历史，正是上下文最膨胀的时刻
        if (contextCompactor != null) {
            contextCompactor.compact(context.messages(), context.question());
        }

        RoundState state = new RoundState();
        // 在发起请求前拍下快照——这轮的历史随后会被 finishRound 原地追加助手消息，晚拍就不是"发出去的那份"了
        String requestSnapshot = (traceStore == null) ? null : MessageRendering.render(context.messages());
        Timer.Sample totalSample = meterRegistry == null ? null : Timer.start(meterRegistry);
        Timer.Sample ttftSample = meterRegistry == null ? null : Timer.start(meterRegistry);
        Timer totalTimer = timer("agenttrail.llm.duration");
        Timer ttftTimer = timer("agenttrail.llm.ttft");
        AtomicBoolean firstChunkSeen = new AtomicBoolean();
        AtomicBoolean mainSubscriptionTerminated = new AtomicBoolean();
        AtomicReference<Disposable> watchdogRef = new AtomicReference<>();
        Disposable subscription = Flux.from(roundDriver.drive(
                        LlmInvoker.toModelRequest(context.messages(), roundTools), roundTools))
                .map(LlmInvoker::toChatResponse)
                // 真实的 HTTP ChatModel（Reactor Netty 实现）在自己的 I/O 线程上信号 onComplete——
                // finishRound 出现工具调用时会走到 ToolCallExecutor.execute() 内部的 .block()，
                // 直接卡在 I/O 线程上会被 Reactor 的非阻塞线程检查拒绝；统一切换到弹性线程。
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> {
                    if (firstChunkSeen.compareAndSet(false, true)) {
                        stopTimer(ttftSample, ttftTimer);
                    }
                    processChunk(chunk, state, context);
                })
                .doOnComplete(() -> {
                    stopTimer(totalSample, totalTimer);
                    finishRound(state, context, requestSnapshot, skillTool);
                })
                .doOnError(error -> {
                    stopTimer(totalSample, totalTimer);
                    failRun(error, context, state, requestSnapshot);
                })
                // failRun 已把异常转成协议内的 Error + Complete；继续把 error 冒给无 error consumer 的
                // subscribe 只会制造 onErrorDropped 噪声，且前端不会得到任何额外信息。
                .onErrorComplete()
                .doFinally(signalType -> {
                    mainSubscriptionTerminated.set(true);
                    Disposable watchdog = watchdogRef.getAndSet(null);
                    if (watchdog != null) {
                        watchdog.dispose();
                    }
                })
                .subscribe();

        // 每轮都要重新登记，否则停止请求作用在上一轮早已结束的订阅上（踩坑点 #9）
        taskManager.setDisposable(context.conversationId(), subscription);
        Disposable watchdog = scheduleRoundWatchdog(context, state, requestSnapshot, subscription);
        if (mainSubscriptionTerminated.get()) {
            watchdog.dispose();
        } else {
            watchdogRef.set(watchdog);
            // The stream may terminate between the check and the set above.
            if (mainSubscriptionTerminated.get() && watchdogRef.compareAndSet(watchdog, null)) {
                watchdog.dispose();
            }
        }
    }

    /**
     * 绝对时钟兜底（踩坑点 #93）：不看这一轮此刻处于什么状态，只看 {@code roundTimeout} 之后
     * {@code subscription} 是不是还没结束——{@code Disposable#isDisposed()} 在订阅正常完成/出错
     * 时会自然变 true（不需要显式调用 {@code dispose()}），所以这里不用关心"要不要在成功路径上
     * 提前取消这个 watchdog"：到点一看已经结束了，直接返回，不做任何事，每轮多出的这一次
     * 延迟调度是唯一的常驻代价。真正命中超时时才会执行 dispose + failRun，和其它失败路径
     * 走同一套收尾逻辑（落库、Hook、释放单飞锁、给前端一个明确的 Error 事件）。
     */
    private Disposable scheduleRoundWatchdog(RunContext context, RoundState state, String requestSnapshot,
                                            Disposable subscription) {
        return Mono.delay(roundTimeout).subscribe(tick -> {
            if (subscription.isDisposed()) {
                return;
            }
            subscription.dispose();
            failRun(new TimeoutException("round exceeded absolute timeout of " + roundTimeout),
                    context, state, requestSnapshot);
        });
    }

    private Timer timer(String name) {
        if (meterRegistry == null) {
            return null;
        }
        return Timer.builder(name)
                .description("AgentTrail LLM timing")
                .tag("model", modelName)
                .register(meterRegistry);
    }

    private static void stopTimer(Timer.Sample sample, Timer timer) {
        if (sample != null && timer != null) {
            sample.stop(timer);
        }
    }

    /**
     * 本轮该暴露给模型的工具清单：固定工具 + 检索元工具本身 + 这次会话目前为止已经搜到的工具
     * + 这一轮现取的 Skill 工具（如果配了 {@link #skillManager}）。
     *
     * <p>"搜到"和"能调用"之间天然隔一轮：工具在第 N 轮的工具调用里被搜索元工具发现，
     * discoveredNames 立刻更新，但第 N 轮已经在用（甚至已经收到）的模型响应不会重新协商工具清单——
     * 只有第 N+1 轮重新组装 roundTools 时，新发现的工具才第一次出现在模型可选列表里。
     *
     * @param skillTool 调用方（{@link #scheduleRound}）这一轮现取的结果；传 null 表示这一轮不挂 Skill 工具
     */
    private ToolCallback resolveSkillTool(RunContext context) {
        if (skillManager == null) {
            return null;
        }
        AtomicReference<java.util.Optional<ToolCallback>> cache = context.cachedSkillTool();
        java.util.Optional<ToolCallback> cached = cache.get();
        if (cached == null) {
            cached = skillManager.buildSkillsTool();
            cache.set(cached);
        }
        return cached.orElse(null);
    }

    private List<ToolCallback> withDiscoveredTools(ToolSearchSession toolSearchSession, ToolCallback skillTool) {
        if (toolSearchSession == null && skillTool == null) {
            return tools;
        }
        List<ToolCallback> roundTools = new ArrayList<>(tools);
        if (toolSearchSession != null) {
            roundTools.add(toolSearchSession.toolSearchCallback());
            roundTools.addAll(toolSearchSession.discoveredTools());
        }
        if (skillTool != null) {
            roundTools.add(skillTool);
        }
        return roundTools;
    }

    /** 处理单个流式 chunk：工具调用分片、正文文本、独立字段里的思考内容，三者都可能出现。 */
    private void processChunk(ChatResponse chunk, RoundState state, RunContext context) {
        // usage 常常搭在最后一个只报统计信息、result 为空的 chunk 上，所以要在下面的空检查之前处理
        if (chunk.getMetadata() != null && chunk.getMetadata().getUsage() != null) {
            var usage = chunk.getMetadata().getUsage();
            state.acceptUsage(usage.getPromptTokens(), usage.getCompletionTokens());
        }

        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();

        // 思考内容可能和工具调用出现在同一个 chunk 里，所以先无条件处理它
        thinkingModeProcessor.processReasoning(output, state, context.sink());

        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(state::acceptToolCall);
            return;
        }
        thinkingModeProcessor.processText(output.getText(), state, context.sink());
    }

    /**
     * 一轮流结束后的分支：无工具调用即终局；有工具调用则执行、拼回消息、递归下一轮。
     *
     * @param skillTool 和这一轮喂给模型看的是同一个实例（见 {@link #scheduleRound}），
     *                  保证"模型看到的"和"执行层能解析到的"是同一份技能清单，不会重新查一次库
     */
    private void finishRound(RoundState state, RunContext context, String requestSnapshot, ToolCallback skillTool) {
        // 先让标签解析器把攒住的尾巴吐出来，否则最后几个字会丢
        thinkingModeProcessor.finishRound(state, context.sink());

        if (state.mode() == RoundMode.TEXT) {
            recordTrace(context, state, requestSnapshot, state.text(), true, null);
            fireBudget(context, state);
            recordBudget(context, state);
            completeRun(state, context);
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = state.toolCalls();
        // 先把带 tool_calls 的助手消息落进历史，再落工具结果——顺序颠倒模型侧会解析失败
        context.messages().add(buildAssistantMessage(state, toolCalls));
        toolRoundExecutor.validate(toolCalls.stream()
                .map(call -> new ToolRoundExecutor.ToolCall(call.name(), call.id(), call.arguments()))
                .toList());
        // 只记这轮"模型要调什么工具"，不记工具执行结果——结果会随下一轮历史出现在下一条记录的输入里
        recordTrace(context, state, requestSnapshot, MessageRendering.renderToolCalls(toolCalls), true, null);
        fireBudget(context, state);
        recordBudget(context, state);

        if (requiresApproval(toolCalls)) {
            pauseForApproval(toolCalls, context);
            return;
        }

        ToolParamInjector paramInjector = new ToolParamInjector(context.params().toolParams());
        // toolSearchSession 和 skillTool 在当前生产装配下互斥（见 skillManager 字段的说明），
        // 复用同一个"会话专属工具"解析槽位不会撞车；两者都为空时这里就是 null，行为和以前一致
        ToolCallback sessionScopedTool = (context.toolSearchSession() != null)
                ? context.toolSearchSession().toolSearchCallback() : skillTool;
        HookContext hookContext = toHookContext(context);
        firePreToolUse(hookContext, toolCalls);

        List<ToolResponseMessage.ToolResponse> responses = executeWithRateLimit(
                toolCalls, context, paramInjector, sessionScopedTool);
        firePostToolUse(hookContext, responses);
        context.messages().add(ToolResponseMessage.builder().responses(responses).build());

        // 这一批工具调用（一轮可能并发跑多个）跑完了——不是每个工具单独触发一次，见 StageTiming.AFTER_TOOL_END
        stageOutputManager.afterToolEnd(new StageContext(context.question(), null, context.params()), context::emit);

        if (maxConsecutiveToolFailures > 0) {
            String breakerMessage = checkConsecutiveToolFailures(context, responses);
            if (breakerMessage != null) {
                state.appendText(breakerMessage);
                context.emit(new AgentStreamEvent.Text(breakerMessage));
                completeRun(state, context);
                return;
            }
        }

        if (budgetTracker != null && budgetTracker.overBudget(context.conversationId())) {
            String message = "本会话 token 消耗已超过预算上限，本轮到此为止——如需继续，请开启新会话。";
            state.appendText(message);
            context.emit(new AgentStreamEvent.Text(message));
            completeRun(state, context);
            return;
        }

        scheduleRound(context);
    }

    /**
     * 按工具名统计"连续失败"次数：本轮里一个工具调用失败就把它的计数加一，一旦某次成功
     * 就把那个工具名的计数清零——不是全局失败总数，是"最近这几次是不是一直在原地打转"。
     * 达到阈值时返回一段说明文字（附最近一次的错误原因），调用方据此提前收尾，不再进入下一轮；
     * 没有任何工具越过阈值时返回 null，正常继续。
     *
     * <p>{@code maxRounds} 早就是硬顶，但那是粗粒度的"整轮最多转几圈"，不区分"这几圈都在拿同一个
     * 工具反复试错"——ReAct+Skill 路线没有 DataAgent 那种 Gate+maxRetries 的图结构上限，SKILL.md
     * 写的重试预算只是给模型的指导，不是强制；这里补一道 Runtime 级别真正有强制力的止损线。
     */
    private String checkConsecutiveToolFailures(RunContext context, List<ToolResponseMessage.ToolResponse> responses) {
        String breakerMessage = null;
        for (ToolResponseMessage.ToolResponse response : responses) {
            String name = response.name();
            if (looksLikeToolFailure(response.responseData())) {
                int count = context.consecutiveToolFailures().merge(name, 1, Integer::sum);
                if (count >= maxConsecutiveToolFailures) {
                    breakerMessage = "工具 " + name + " 已连续 " + count + " 次调用失败，最近一次的错误是：" +
                            truncate(response.responseData(), 300) +
                            "。为避免无意义的重复重试，本轮到此为止——请根据以上错误信息调整问题，或换一种问法重新提问。";
                }
            } else {
                context.consecutiveToolFailures().remove(name);
            }
        }
        return breakerMessage;
    }

    /** 业务工具约定失败结果以 {@code "Error:"} 开头（见各 analytics 工具）；{@code ToolCallExecutor}
     *  自己捕获的引擎级失败（未知工具、执行异常）用 {@code {"error":...}} 这个 JSON 形状——两种都算失败。 */
    private static boolean looksLikeToolFailure(String result) {
        return result != null && (result.startsWith("Error:") || result.startsWith("{\"error\""));
    }

    private static String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength) + "…";
    }

    private boolean requiresApproval(List<AssistantMessage.ToolCall> toolCalls) {
        return pauseConfig != null && toolCalls.stream().anyMatch(call -> pauseConfig.requiresApproval(call.name()));
    }

    /**
     * 命中审批名单：**整轮**的工具调用都先挂起，不做"名单内的暂停、名单外的照常执行"这种
     * 按调用粒度拆分的处理——一轮里往往有先后依赖，名单外的调用先执行完，审批被拒时那部分
     * 副作用已经无法撤销，模型也很难理解"这轮结果一半生效一半没生效"。落一份完整快照，
     * 简单、可预测，代价只是一次不必要的等待（审批通常也不追求极致的执行效率）。
     */
    private void pauseForApproval(List<AssistantMessage.ToolCall> toolCalls, RunContext context) {
        List<PendingToolCall> pending = toolCalls.stream()
                .map(call -> new PendingToolCall(call.id(), call.name(), call.arguments()))
                .toList();
        PauseState pauseState = new PauseState(context.conversationId(), context.messages(), pending,
                PauseReason.HITL_APPROVAL, SafePoint.BEFORE_TOOL_EXECUTION, context.question(),
                context.params(), context.roundCounter().get(), System.currentTimeMillis());
        pauseConfig.store().save(pauseState);

        context.emit(new AgentStreamEvent.Paused(context.conversationId(), PauseReason.HITL_APPROVAL));
        context.emitComplete();
        // 暂停期间不算"在跑"——占着单飞位只会挡住 resume 走自己的注册流程
        taskManager.removeTask(context.conversationId());
    }

    /**
     * 从一次暂停恢复——{@link #stream} 的姊妹入口，不是它的重载：{@code stream} 从头开始一轮新对话，
     * 本方法接续一个已经存在的 {@link PauseState}，历史、挂起的工具调用、原始运行时参数全部
     * 来自快照，调用方不需要（也不应该）重新提供这些。
     *
     * @param conversationId 要恢复的会话；必须此前调用过 {@link #pauseForApproval} 落过快照
     * @param instruction    按暂停原因决定：{@link ResumeInstruction.ApprovalDecision} 用于 HITL 审批，
     *                       {@link ResumeInstruction.NewInstruction} 用于用户带新指令的中断恢复
     * @throws IllegalStateException    没有配置 {@link PauseConfig}
     * @throws IllegalArgumentException 这个会话没有可恢复的暂停状态
     */
    public Flux<AgentStreamEvent> resume(String conversationId, ResumeInstruction instruction) {
        if (pauseConfig == null) {
            throw new IllegalStateException("未配置 PauseConfig，这个 Runtime 实例不支持暂停/恢复");
        }
        PauseState paused = pauseConfig.store().find(conversationId)
                .orElseThrow(() -> new IllegalArgumentException("会话 " + conversationId + " 没有可恢复的暂停状态"));

        Sinks.Many<AgentStreamEvent> sink = EventSinks.bounded();
        if (!taskManager.registerTask(conversationId, sink)) {
            EventSinks.emit(sink, new AgentStreamEvent.Error("CONCURRENT_EXECUTION", "该会话正在执行中，请稍后再试"));
            sink.tryEmitComplete();
            return sink.asFlux();
        }

        List<Message> messages = new ArrayList<>(paused.messages());
        List<ToolResponseMessage.ToolResponse> responses = resolvePendingToolResponses(paused, instruction, sink);
        if (!responses.isEmpty()) {
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }
        if (instruction instanceof ResumeInstruction.NewInstruction newInstruction) {
            messages.add(new UserMessage(newInstruction.message()));
        }
        // 快照已经消费完毕，不删的话一次异常重复恢复会用一份过期的历史覆盖掉新产生的对话
        pauseConfig.store().delete(conversationId);

        // 从暂停时的轮次续数，而不是从 0 重开一整份 maxRounds 预算——否则反复暂停/恢复能绕开轮次上限
        RunContext context = new RunContext(paused.question(), paused.params(), messages, sink,
                new AtomicInteger(paused.roundAtPause()), System.currentTimeMillis(), null, MDC.getCopyOfContextMap());
        scheduleRound(context);
        return sink.asFlux();
    }

    /**
     * 按恢复方式决定挂起的工具调用该怎么处理：审批通过就真正执行（走和正常轮次一样的
     * {@link ToolCallExecutor}，保留调度器隔离、参数注入这些既有机制）；审批拒绝或者用户
     * 带着新指令中断，都不执行，只是喂回去的占位文本不同——前者说明"为什么没做"，
     * 后者说明"用户换主意了，这个调用不再相关"。
     */
    private List<ToolResponseMessage.ToolResponse> resolvePendingToolResponses(
            PauseState paused, ResumeInstruction instruction, Sinks.Many<AgentStreamEvent> sink) {
        if (paused.pendingToolCalls().isEmpty()) {
            return List.of();
        }
        // sealed 接口 + 穷尽 switch：将来 ResumeInstruction 再加一种分支，编译器会在这里直接报错，
        // 不会像 instanceof 链那样悄悄漏掉一种情况
        return switch (instruction) {
            case ResumeInstruction.NewInstruction ignored ->
                    skipPendingToolCalls(paused, "该调用因用户中断并给出新指令而被跳过，未执行");
            case ResumeInstruction.ApprovalDecision decision when !decision.approved() ->
                    skipPendingToolCalls(paused, "Error: 用户拒绝执行该工具"
                            + (decision.rejectionReason() == null ? "" : "：" + decision.rejectionReason()));
            case ResumeInstruction.ApprovalDecision approved -> executePendingToolCalls(paused, sink);
        };
    }

    private List<ToolResponseMessage.ToolResponse> skipPendingToolCalls(PauseState paused, String message) {
        return paused.pendingToolCalls().stream()
                .map(pending -> new ToolResponseMessage.ToolResponse(pending.id(), pending.name(), message))
                .toList();
    }

    private List<ToolResponseMessage.ToolResponse> executePendingToolCalls(
            PauseState paused, Sinks.Many<AgentStreamEvent> sink) {
        List<AssistantMessage.ToolCall> approvedCalls = paused.pendingToolCalls().stream()
                .map(pending -> new AssistantMessage.ToolCall(pending.id(), "function", pending.name(), pending.arguments()))
                .toList();
        ToolParamInjector paramInjector = new ToolParamInjector(paused.params().toolParams());
        HookContext hookContext = new HookContext(paused.conversationId(), paused.params().userId(),
                paused.roundAtPause());
        firePreToolUse(hookContext, approvedCalls);
        // 这一步发生在恢复流程里还没建出新 RunContext 的时刻（见调用方），没有 context::emit
        // 可用；直接转发到原始 sink，和改造前的行为一致——落库轨迹的记录从下面新建的
        // RunContext 开始才生效，暂停前的工具调用不计入这一轮的 timeline，可接受。
        List<ToolResponseMessage.ToolResponse> responses = toolCallExecutor.execute(
                approvedCalls, event -> EventSinks.emit(sink, event), paramInjector);
        firePostToolUse(hookContext, responses);
        return responses;
    }

    /**
     * 声明了 {@link OutputType} 时（issue #18），把 JSON Schema 格式指令追加到用户提问后面，
     * 只影响真正发给模型的这条 {@link UserMessage}——{@code question} 本身保持原样，
     * 落库、压缩摘要、StageContext 这些消费方看到的还是用户的原始提问，不会混入格式指令噪音。
     */
    private static String withFormatInstruction(String question, OutputType outputType) {
        if (outputType == null) {
            return question;
        }
        return question + "\n" + outputType.formatInstruction();
    }

    /** 无条件注入，不像记忆/文件区块那样有"未启用"的情况——日期不是一个可选能力。 */
    private static String buildDateSection() {
        LocalDate today = LocalDate.now(APP_ZONE);
        String weekday = today.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        return "当前日期：" + today.format(DateTimeFormatter.ofPattern("yyyy年M月d日")) + " " + weekday
                + "。这是你获取当前日期的唯一途径——涉及“今天”“明天”“本周”“最近 N 天”等相对时间的问题，"
                + "以这个日期为准直接回答，不要说自己无法获取当前时间，也不要编造另一个日期。";
    }

    /** 未启用（{@link #memoryStore} 为 null）或用户未知时返回空串——调用方直接据此判断要不要插入。 */
    private String buildMemorySection(String userId) {
        if (memoryStore == null || userId == null) {
            return "";
        }
        return MemoryPromptFormatter.formatSection(memoryStore.findByUserId(userId));
    }

    /** 未启用（{@link #fileStore} 为 null）时返回空串——调用方直接据此判断要不要插入（issue #28）。 */
    private String buildFileSection(String conversationId) {
        if (fileStore == null) {
            return "";
        }
        return FilePromptFormatter.formatSection(fileStore.findByConversationId(conversationId));
    }

    /**
     * content 必须显式给空串而非留 null——部分厂商的 createRequest 对 assistant 消息做了
     * Assert.state(text != null)（#5a⑤）。思考内容写进 reasoning_content metadata 保留下来，
     * 转回具体厂商消息类型的工作交给该厂商的 ChatModel 装饰器，本类不关心对接的是哪家模型。
     */
    private static AssistantMessage buildAssistantMessage(RoundState state, List<AssistantMessage.ToolCall> toolCalls) {
        var builder = AssistantMessage.builder()
                .content(state.text())
                .toolCalls(toolCalls);
        String reasoning = state.reasoning();
        if (!reasoning.isEmpty()) {
            builder.properties(Map.of("reasoning_content", reasoning));
        }
        return builder.build();
    }

    /**
     * 收尾。这里**不再补发正文**——文本已经在 chunk 到达时逐段投递出去了，
     * 收尾时再发一次完整正文会让前端收到两份重复内容。
     *
     * <p>落库必须在 emitComplete 之前同步做完——挂在流关闭之后的收尾回调里，进程退出时
     * 可能根本跑不到，这一轮就白问了（踩坑点 #63）。
     */
    private void completeRun(RoundState state, RunContext context) {
        forgetBudget(context);
        String think = state.reasoning().isEmpty() ? null : state.reasoning();
        Long turnId = persistenceHook == null ? null : persistenceHook.onTurnComplete(new TurnRecord(
                context.conversationId(), context.params().userId(), context.question(),
                state.text(), think, context.toolTimelineJson(), null, context.elapsedMillis()));

        // 上传发生在这一轮结束之前，那时候轮次 id 还不存在，只能等这里拿到 id 才回填（issue #28）
        if (fileStore != null && turnId != null) {
            fileStore.linkFilesToTurn(context.conversationId(), turnId);
        }

        // 和落库一样必须在 emitComplete 之前同步做完；提取本身失败会被 MemoryExtractor 内部吞掉，
        // 不会因为这一步把整轮对话搞崩
        if (memoryExtractor != null) {
            memoryExtractor.extractAndSave(context.params().userId(), context.question(), state.text());
        }

        // 答案已经确定，Complete 之前留给 provider 一次机会插引用链接/推荐问题这类收尾输出
        stageOutputManager.beforeComplete(
                new StageContext(context.question(), state.text(), context.params()), context::emit);

        fireSessionEnd(context, true);
        completionCoordinator.complete(context);
        context.emit(new AgentStreamEvent.Complete(context.conversationId(), turnId));
        context.emitComplete();
        // 释放单飞占位，让该会话能发起下一轮对话
        taskManager.removeTask(context.conversationId());
    }

    private void failRun(Throwable error, RunContext context, RoundState state, String requestSnapshot) {
        forgetBudget(context);
        recordTrace(context, state, requestSnapshot, null, false, error.getMessage());
        fireOnError(context, error);
        fireSessionEnd(context, false);
        context.emit(new AgentStreamEvent.Error("LLM_CALL_FAILED", error.getMessage()));
        context.emitComplete();
        taskManager.removeTask(context.conversationId());
    }

    /** 未配置 {@link #traceStore} 时是纯粹的空操作——调用方在此之前已经决定好是否要渲染快照。 */
    private void recordTrace(RunContext context, RoundState state, String requestSnapshot,
                             String outputData, boolean success, String errorMessage) {
        if (traceStore == null) {
            return;
        }
        String think = state.reasoning().isEmpty() ? null : state.reasoning();
        long durationMillis = System.currentTimeMillis() - state.startMillis();
        traceStore.save(new TraceRecord(context.conversationId(), context.roundCounter().get(),
                requestSnapshot, outputData, think, state.promptTokens(), state.completionTokens(),
                durationMillis, success, errorMessage, System.currentTimeMillis()));
    }

    private void fireSessionStart(RunContext context) {
        HookContext hookContext = new HookContext(context.conversationId(), context.params().userId(), 0);
        hooks.sessionStart().forEach(hook -> hook.onSessionStart(hookContext));
    }

    private void fireBudget(RunContext context, RoundState state) {
        HookContext hookContext = toHookContext(context);
        hooks.budget().forEach(hook -> hook.onRoundUsage(
                hookContext, state.promptTokens(), state.completionTokens()));
    }

    private void recordBudget(RunContext context, RoundState state) {
        if (budgetTracker != null) {
            budgetTracker.record(context.conversationId(), state.promptTokens(), state.completionTokens());
        }
    }

    private void forgetBudget(RunContext context) {
        if (budgetTracker != null) {
            budgetTracker.forget(context.conversationId());
        }
    }

    /**
     * 限速在真正执行前把超限的调用摘出来，只让剩下的走 {@link ToolCallExecutor}——没有直接
     * 把 {@link ToolRateLimiter} 套进 {@link com.agenttrail.loop.hook.PreToolUseHook}：那个
     * 接口是纯观察型 void 契约（Ticket 1 的既定设计），用异常做流程控制会拉伸它的契约，
     * 不如像 {@link SessionBudgetTracker} 一样单独做一个直接参与决策的组件更清楚。
     *
     * <p>被限速的调用不送进 {@link ToolCallExecutor}，直接合成一条 {@code "Error:"} 开头的
     * {@link ToolResponseMessage.ToolResponse} 喂回模型——跟业务工具约定失败结果的形状一致，
     * 也会被 {@link #checkConsecutiveToolFailures} 当作一次失败计入连续失败熔断。
     */
    private List<ToolResponseMessage.ToolResponse> executeWithRateLimit(
            List<AssistantMessage.ToolCall> toolCalls, RunContext context,
            ToolParamInjector paramInjector, ToolCallback sessionScopedTool) {
        if (toolRateLimiter == null) {
            return toolCallExecutor.execute(
                    toolCalls, context::emit, paramInjector, sessionScopedTool, context.mdcSnapshot());
        }

        List<AssistantMessage.ToolCall> allowed = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : toolCalls) {
            if (toolRateLimiter.allow(context.conversationId())) {
                allowed.add(call);
            } else {
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        "Error: 工具调用频率超限，本次调用已被拒绝——请放慢调用节奏，" +
                                "或基于已有结果继续推理，不要连续重试同一个工具"));
            }
        }
        if (!allowed.isEmpty()) {
            responses.addAll(toolCallExecutor.execute(
                    allowed, context::emit, paramInjector, sessionScopedTool, context.mdcSnapshot()));
        }
        return responses;
    }

    private void firePreToolUse(HookContext context, List<AssistantMessage.ToolCall> toolCalls) {
        toolCalls.forEach(call -> {
            ToolInvocation invocation = new ToolInvocation(call.id(), call.name(), call.arguments());
            hooks.preToolUse().forEach(hook -> hook.beforeToolUse(context, invocation));
        });
    }

    private void firePostToolUse(HookContext context, List<ToolResponseMessage.ToolResponse> responses) {
        responses.forEach(response -> {
            ToolInvocation invocation = new ToolInvocation(response.id(), response.name(), null);
            boolean success = !looksLikeToolFailure(response.responseData());
            hooks.postToolUse().forEach(hook -> hook.afterToolUse(
                    context, invocation, response.responseData(), success));
        });
    }

    private void fireOnError(RunContext context, Throwable error) {
        hooks.onError().forEach(hook -> hook.onError(toHookContext(context), error));
    }

    private void fireSessionEnd(RunContext context, boolean success) {
        hooks.sessionEnd().forEach(hook -> hook.onSessionEnd(toHookContext(context), success));
    }

    private static HookContext toHookContext(RunContext context) {
        return new HookContext(context.conversationId(), context.params().userId(),
                context.roundCounter().get());
    }
}
