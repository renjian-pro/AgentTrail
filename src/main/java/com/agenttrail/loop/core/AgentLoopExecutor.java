package com.agenttrail.loop.core;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.context.MessageRendering;
import com.agenttrail.loop.file.FilePromptFormatter;
import com.agenttrail.loop.file.FileStore;
import com.agenttrail.loop.memory.MemoryExtractor;
import com.agenttrail.loop.memory.MemoryPromptFormatter;
import com.agenttrail.loop.memory.MemoryStore;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.OutputType;
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
import com.agenttrail.loop.stageoutput.StageContext;
import com.agenttrail.loop.stageoutput.StageOutputManager;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.trace.TraceRecord;
import com.agenttrail.loop.trace.TraceStore;
import com.agenttrail.loop.structured.JsonRepair;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.search.ToolSearchSession;
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
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 手写 ReAct 循环：一轮 = 一次流式模型调用 +（如果模型要调工具）一批工具执行 + 递归进入下一轮。
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

    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        this(chatModel, tools, maxRounds, new AgentTaskManager(), null, ThinkingMode.DISABLED, null);
    }

    /**
     * @param taskManager     任务管理器由外部传入并**共享**——停止接口要能找到正在跑的任务，
     *                        每次请求各自 new 一个的话，停止请求永远找不到目标
     * @param contextPolicy   上下文压缩策略；传 null 表示不压缩，循环行为与没有该机制时一致
     * @param thinkingMode    当前所用模型交付思考过程的方式
     * @param persistenceHook 会话持久化回调；传 null 表示不落库、不预加载历史（如子 Agent）
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook, null, null);
    }

    /**
     * @param toolCatalog ToolSearch 延迟工具池；传 null 表示不启用该机制，行为与没有它时完全一致。
     *                    池子里的工具**始终**可以被执行层解析到（{@link ToolCallExecutor} 按全量池建表），
     *                    但只有本次对话已经"搜到"的那部分才会被下一轮的工具清单暴露给模型——
     *                    可见性限制只在喂给 LLM 那一侧，执行层不做二次过滤
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook, toolCatalog, null);
    }

    /**
     * @param pauseConfig 暂停/恢复机制（issue #13）；传 null 表示完全不启用，行为与没有它时一致。
     *                    命中 {@code pauseConfig} 审批名单的工具调用不会被执行，循环改为落一份
     *                    {@link PauseState} 快照、发 {@link AgentStreamEvent.Paused} 事件后结束，
     *                    之后只能通过 {@link #resume} 恢复，而不是靠再调一次 {@link #stream}
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog, PauseConfig pauseConfig) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook,
                toolCatalog, pauseConfig, null);
    }

    /**
     * @param stageOutputManager 分阶段输出机制（issue #16）；传 null 等价于 {@link StageOutputManager#EMPTY}，
     *                           三个生命周期钩子都是空操作，行为和没有这个机制时完全一致
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog, PauseConfig pauseConfig,
                             StageOutputManager stageOutputManager) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook,
                toolCatalog, pauseConfig, stageOutputManager, null);
    }

    /**
     * @param traceStore 追踪审计存储（issue #17）；传 null 表示不启用——不记录任何一轮，
     *                   也不做消息渲染这类额外开销，行为与没有这个机制时完全一致。这个参数本身
     *                   就是"显式开关"：要启用就必须主动传一个实现进来
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog, PauseConfig pauseConfig,
                             StageOutputManager stageOutputManager, TraceStore traceStore) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook,
                toolCatalog, pauseConfig, stageOutputManager, traceStore, null);
    }

    /**
     * @param memoryStore 分层记忆体系的中间层存储（issue #19：画像/偏好/指令/事实）；传 null 表示
     *                    不启用——既不在 {@link #stream} 里读取注入，也不在收尾时提取，行为与没有
     *                    这个机制时完全一致。短期历史层由 {@code persistenceHook} 负责；跨会话语义
     *                    摘要层依赖向量库，留给 Phase 4，不是这个参数管的范围
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog, PauseConfig pauseConfig,
                             StageOutputManager stageOutputManager, TraceStore traceStore,
                             MemoryStore memoryStore) {
        this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook,
                toolCatalog, pauseConfig, stageOutputManager, traceStore, memoryStore, null);
    }

    /**
     * @param fileStore 文件问答的元数据存储（issue #28）；传 null 表示不启用——既不在
     *                  {@link #stream} 里注入"会话文件"区块，也不在收尾时回填 turnId，
     *                  行为与没有这个机制时完全一致。文件的解析/向量化/多模态识别由
     *                  {@code FileQaService} 编排（issue #21/#26/#27），本类只负责
     *                  "让模型看见这一轮和历史上传了哪些文件"+"轮次结束后回填归属"
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager, ContextPolicy contextPolicy,
                             ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                             ToolCatalog toolCatalog, PauseConfig pauseConfig,
                             StageOutputManager stageOutputManager, TraceStore traceStore,
                             MemoryStore memoryStore, FileStore fileStore) {
        this.llmInvoker = new LlmInvoker(chatModel);
        this.toolCallExecutor = new ToolCallExecutor(withDeferredPool(tools, toolCatalog));
        this.taskManager = taskManager;
        this.contextCompactor = (contextPolicy == null) ? null : new ContextCompactor(contextPolicy, chatModel);
        this.thinkingModeProcessor = new ThinkingModeProcessor(thinkingMode);
        this.persistenceHook = persistenceHook;
        this.tools = tools;
        this.toolCatalog = toolCatalog;
        this.pauseConfig = pauseConfig;
        this.stageOutputManager = (stageOutputManager == null) ? StageOutputManager.EMPTY : stageOutputManager;
        this.traceStore = traceStore;
        this.memoryStore = memoryStore;
        this.memoryExtractor = (memoryStore == null) ? null : new MemoryExtractor(chatModel, memoryStore);
        this.fileStore = fileStore;
        this.maxRounds = maxRounds;
    }

    /**
     * 起一个 builder：{@code chatModel}/{@code tools}/{@code maxRounds} 是唯一必填项，其余可选机制
     * 通过命名方法设置。比起继续在telescoping 构造函数链上叠新重载——每加一个可选机制就多一个
     * 只为它而生的重载，调用方为了设最后一个参数得排一串 {@code null} 占位——这里改用命名方法，
     * 加错顺序或漏传一个不会被编译器悄悄放过。不影响、不替换现有构造函数，两种装配方式并存。
     */
    public static Builder builder(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        return new Builder(chatModel, tools, maxRounds);
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

        public AgentLoopExecutor build() {
            return new AgentLoopExecutor(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode,
                    persistenceHook, toolCatalog, pauseConfig, stageOutputManager, traceStore, memoryStore,
                    fileStore);
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

        List<Message> messages = new ArrayList<>();
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
        messages.add(new UserMessage(withFormatInstruction(question, params.outputType())));

        // 每次对话请求各自开一个全新会话——发现的工具互相隔离，不会泄漏给并发的其他会话
        ToolSearchSession toolSearchSession = (toolCatalog == null) ? null : toolCatalog.newSession();

        // 工具执行会跳到独立调度器的线程上（见 ToolCallExecutor），MDC 这种 ThreadLocal 状态
        // 过了线程边界就读不到了；这里在还没跳线程之前，把发起这次请求的线程的 MDC 拍下来
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        RunContext context = new RunContext(question, params, messages, sink, new AtomicInteger(0),
                System.currentTimeMillis(), toolSearchSession, mdcSnapshot);
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
     * 调度一轮：发起流式请求，边收边攒，流结束后再决定"收尾"还是"执行工具并进入下一轮"。
     *
     * <p>之所以要等整个流结束才决策，是因为一轮的性质（文本轮 / 工具调用轮）在流跑完之前
     * 是不确定的，详见 {@link RoundState}。
     */
    private void scheduleRound(RunContext context) {
        // 超出轮次预算后，本轮改成不挂任何工具——模型看不见工具，就没法再发起调用
        boolean toolsExhausted = maxRounds > 0 && context.nextRound() > maxRounds;
        List<ToolCallback> roundTools = toolsExhausted ? List.of() : withDiscoveredTools(context.toolSearchSession());

        // 压缩放在发请求之前：此时上一轮的工具结果刚落进历史，正是上下文最膨胀的时刻
        if (contextCompactor != null) {
            contextCompactor.compact(context.messages(), context.question());
        }

        RoundState state = new RoundState();
        // 在发起请求前拍下快照——这轮的历史随后会被 finishRound 原地追加助手消息，晚拍就不是"发出去的那份"了
        String requestSnapshot = (traceStore == null) ? null : MessageRendering.render(context.messages());
        Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
                // 真实的 HTTP ChatModel（Reactor Netty 实现）在自己的 I/O 线程上信号 onComplete——
                // finishRound 出现工具调用时会走到 ToolCallExecutor.execute() 内部的 .block()，
                // 直接卡在 I/O 线程上会被 Reactor 的非阻塞线程检查拒绝
                // （IllegalStateException: block()... not supported in thread reactor-http-nio-*）。
                // 挂 ScriptedChatModel 的测试从没触发过这条检查——它不是真的 Reactor Netty 实现，
                // 这个坑只有接真实模型 + 真实工具调用同时发生才会暴露。
                .publishOn(Schedulers.boundedElastic())
                .doOnNext(chunk -> processChunk(chunk, state, context))
                .doOnComplete(() -> finishRound(state, context, requestSnapshot))
                .doOnError(error -> failRun(error, context, state, requestSnapshot))
                .subscribe();

        // 每轮都要重新登记，否则停止请求作用在上一轮早已结束的订阅上（踩坑点 #9）
        taskManager.setDisposable(context.conversationId(), subscription);
    }

    /**
     * 本轮该暴露给模型的工具清单：固定工具 + 检索元工具本身 + 这次会话目前为止已经搜到的工具。
     *
     * <p>"搜到"和"能调用"之间天然隔一轮：工具在第 N 轮的工具调用里被搜索元工具发现，
     * discoveredNames 立刻更新，但第 N 轮已经在用（甚至已经收到）的模型响应不会重新协商工具清单——
     * 只有第 N+1 轮重新组装 roundTools 时，新发现的工具才第一次出现在模型可选列表里。
     */
    private List<ToolCallback> withDiscoveredTools(ToolSearchSession toolSearchSession) {
        if (toolSearchSession == null) {
            return tools;
        }
        List<ToolCallback> roundTools = new ArrayList<>(tools);
        roundTools.add(toolSearchSession.toolSearchCallback());
        roundTools.addAll(toolSearchSession.discoveredTools());
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

    /** 一轮流结束后的分支：无工具调用即终局；有工具调用则执行、拼回消息、递归下一轮。 */
    private void finishRound(RoundState state, RunContext context, String requestSnapshot) {
        // 先让标签解析器把攒住的尾巴吐出来，否则最后几个字会丢
        thinkingModeProcessor.finishRound(state, context.sink());

        if (state.mode() == RoundMode.TEXT) {
            recordTrace(context, state, requestSnapshot, state.text(), true, null);
            completeRun(state, context);
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = state.toolCalls();
        // 先把带 tool_calls 的助手消息落进历史，再落工具结果——顺序颠倒模型侧会解析失败
        context.messages().add(buildAssistantMessage(state, toolCalls));
        // 只记这轮"模型要调什么工具"，不记工具执行结果——结果会随下一轮历史出现在下一条记录的输入里
        recordTrace(context, state, requestSnapshot, MessageRendering.renderToolCalls(toolCalls), true, null);

        if (requiresApproval(toolCalls)) {
            pauseForApproval(toolCalls, context);
            return;
        }

        ToolParamInjector paramInjector = new ToolParamInjector(context.params().toolParams());
        ToolCallback sessionScopedTool = (context.toolSearchSession() == null)
                ? null : context.toolSearchSession().toolSearchCallback();
        List<ToolResponseMessage.ToolResponse> responses = toolCallExecutor.execute(
                toolCalls, context.sink(), paramInjector, sessionScopedTool, context.mdcSnapshot());
        context.messages().add(ToolResponseMessage.builder().responses(responses).build());

        // 这一批工具调用（一轮可能并发跑多个）跑完了——不是每个工具单独触发一次，见 StageTiming.AFTER_TOOL_END
        stageOutputManager.afterToolEnd(new StageContext(context.question(), null, context.params()), context::emit);

        scheduleRound(context);
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
        return toolCallExecutor.execute(approvedCalls, sink, paramInjector);
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
        String think = state.reasoning().isEmpty() ? null : state.reasoning();
        Long turnId = persistenceHook == null ? null : persistenceHook.onTurnComplete(new TurnRecord(
                context.conversationId(), context.params().userId(), context.question(),
                state.text(), think, null, null, context.elapsedMillis()));

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

        context.emit(new AgentStreamEvent.Complete(context.conversationId(), turnId));
        context.emitComplete();
        // 释放单飞占位，让该会话能发起下一轮对话
        taskManager.removeTask(context.conversationId());
    }

    private void failRun(Throwable error, RunContext context, RoundState state, String requestSnapshot) {
        recordTrace(context, state, requestSnapshot, null, false, error.getMessage());
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
}
