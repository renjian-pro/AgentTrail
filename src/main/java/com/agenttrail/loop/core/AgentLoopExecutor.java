package com.agenttrail.loop.core;

import com.agenttrail.loop.context.ContextCompactor;
import com.agenttrail.loop.context.ContextPolicy;
import com.agenttrail.loop.model.AgentStreamEvent;
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
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.search.ToolSearchSession;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.slf4j.MDC;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
    private final int maxRounds;

    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        this(chatModel, tools, maxRounds, new AgentTaskManager(), null, ThinkingMode.DISABLED, null, null, null);
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
        this.llmInvoker = new LlmInvoker(chatModel);
        this.toolCallExecutor = new ToolCallExecutor(withDeferredPool(tools, toolCatalog));
        this.taskManager = taskManager;
        this.contextCompactor = (contextPolicy == null) ? null : new ContextCompactor(contextPolicy, chatModel);
        this.thinkingModeProcessor = new ThinkingModeProcessor(thinkingMode);
        this.persistenceHook = persistenceHook;
        this.tools = tools;
        this.toolCatalog = toolCatalog;
        this.pauseConfig = pauseConfig;
        this.maxRounds = maxRounds;
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
        if (persistenceHook != null) {
            messages.addAll(persistenceHook.loadHistory(params.conversationId(), HISTORY_TOKEN_BUDGET));
        }
        messages.add(new UserMessage(question));

        // 每次对话请求各自开一个全新会话——发现的工具互相隔离，不会泄漏给并发的其他会话
        ToolSearchSession toolSearchSession = (toolCatalog == null) ? null : toolCatalog.newSession();

        // 工具执行会跳到独立调度器的线程上（见 ToolCallExecutor），MDC 这种 ThreadLocal 状态
        // 过了线程边界就读不到了；这里在还没跳线程之前，把发起这次请求的线程的 MDC 拍下来
        Map<String, String> mdcSnapshot = MDC.getCopyOfContextMap();

        RunContext context = new RunContext(question, params, messages, sink, new AtomicInteger(0),
                System.currentTimeMillis(), toolSearchSession, mdcSnapshot);
        scheduleRound(context);
        return sink.asFlux();
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
        Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
                .doOnNext(chunk -> processChunk(chunk, state, context))
                .doOnComplete(() -> finishRound(state, context))
                .doOnError(error -> failRun(error, context))
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
    private void finishRound(RoundState state, RunContext context) {
        // 先让标签解析器把攒住的尾巴吐出来，否则最后几个字会丢
        thinkingModeProcessor.finishRound(state, context.sink());

        if (state.mode() == RoundMode.TEXT) {
            completeRun(state, context);
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = state.toolCalls();
        // 先把带 tool_calls 的助手消息落进历史，再落工具结果——顺序颠倒模型侧会解析失败
        context.messages().add(buildAssistantMessage(state, toolCalls));

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
                context.params(), System.currentTimeMillis());
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

        RunContext context = new RunContext(paused.question(), paused.params(), messages, sink,
                new AtomicInteger(0), System.currentTimeMillis(), null, MDC.getCopyOfContextMap());
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
        if (instruction instanceof ResumeInstruction.NewInstruction) {
            return paused.pendingToolCalls().stream()
                    .map(pending -> new ToolResponseMessage.ToolResponse(pending.id(), pending.name(),
                            "该调用因用户中断并给出新指令而被跳过，未执行"))
                    .toList();
        }
        ResumeInstruction.ApprovalDecision decision = (ResumeInstruction.ApprovalDecision) instruction;
        if (!decision.approved()) {
            String reason = (decision.rejectionReason() == null) ? "" : "：" + decision.rejectionReason();
            return paused.pendingToolCalls().stream()
                    .map(pending -> new ToolResponseMessage.ToolResponse(pending.id(), pending.name(),
                            "Error: 用户拒绝执行该工具" + reason))
                    .toList();
        }

        List<AssistantMessage.ToolCall> approvedCalls = paused.pendingToolCalls().stream()
                .map(pending -> new AssistantMessage.ToolCall(pending.id(), "function", pending.name(), pending.arguments()))
                .toList();
        ToolParamInjector paramInjector = new ToolParamInjector(paused.params().toolParams());
        return toolCallExecutor.execute(approvedCalls, sink, paramInjector);
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

        context.emit(new AgentStreamEvent.Complete(context.conversationId(), turnId));
        context.emitComplete();
        // 释放单飞占位，让该会话能发起下一轮对话
        taskManager.removeTask(context.conversationId());
    }

    private void failRun(Throwable error, RunContext context) {
        context.emit(new AgentStreamEvent.Error("LLM_CALL_FAILED", error.getMessage()));
        context.emitComplete();
        taskManager.removeTask(context.conversationId());
    }
}
