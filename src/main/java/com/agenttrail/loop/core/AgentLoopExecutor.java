package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.task.AgentTaskManager;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 手写 ReAct 循环：一轮 = 一次流式模型调用 +（如果模型要调工具）一批工具执行 + 递归进入下一轮。
 *
 * <p>终止条件只有一个——某一轮从头到尾没有出现工具调用，那一轮的文本就是最终答案。
 * 不依赖模型自报"我说完了"这类约定，因为那属于"提示词当契约"，模型不遵守时无法兜底。
 *
 * <p>工具执行权完全在本类手里：模型调用直接打到 {@link ChatModel#stream}，不经过
 * {@code ChatClient}/Advisor 链（见 ADR-0002），因此框架层的自动工具执行天然不会发生。
 */
public class AgentLoopExecutor {

    private final LlmInvoker llmInvoker;
    private final ToolCallExecutor toolCallExecutor;
    private final AgentTaskManager taskManager;
    private final List<ToolCallback> tools;
    private final int maxRounds;

    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        this(chatModel, tools, maxRounds, new AgentTaskManager());
    }

    /**
     * @param taskManager 任务管理器由外部传入并**共享**——停止接口要能找到正在跑的任务，
     *                    每次请求各自 new 一个的话，停止请求永远找不到目标
     */
    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                             AgentTaskManager taskManager) {
        this.llmInvoker = new LlmInvoker(chatModel);
        this.toolCallExecutor = new ToolCallExecutor(tools);
        this.taskManager = taskManager;
        this.tools = tools;
        this.maxRounds = maxRounds;
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
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

        if (!taskManager.registerTask(params.conversationId(), sink)) {
            sink.tryEmitNext(new AgentStreamEvent.Error("CONCURRENT_EXECUTION", "该会话正在执行中，请稍后再试"));
            sink.tryEmitComplete();
            return sink.asFlux();
        }

        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(question));

        scheduleRound(messages, sink, params, new AtomicInteger(0));
        return sink.asFlux();
    }

    /**
     * 调度一轮：发起流式请求，边收边攒，流结束后再决定"收尾"还是"执行工具并进入下一轮"。
     *
     * <p>之所以要等整个流结束才决策，是因为一轮的性质（文本轮 / 工具调用轮）在流跑完之前
     * 是不确定的，详见 {@link RoundState}。
     *
     * @param roundCounter 跨轮共享的轮次计数器；因为递归发生在异步回调里，用 AtomicInteger
     *                     而不是方法参数传值，保证每一层看到的是同一个计数
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink,
                               RunnableParams params, AtomicInteger roundCounter) {
        boolean toolsExhausted = maxRounds > 0 && roundCounter.incrementAndGet() > maxRounds;
        // 超出轮次预算后，本轮改成不挂任何工具——模型看不见工具，就没法再发起调用
        List<ToolCallback> roundTools = toolsExhausted ? List.of() : tools;
        RoundState state = new RoundState();

        Disposable subscription = llmInvoker.streamRound(messages, roundTools)
                .doOnNext(chunk -> processChunk(chunk, state))
                .doOnComplete(() -> finishRound(messages, state, sink, params, roundCounter))
                .doOnError(err -> {
                    sink.tryEmitNext(new AgentStreamEvent.Error("LLM_CALL_FAILED", err.getMessage()));
                    sink.tryEmitComplete();
                    taskManager.removeTask(params.conversationId());
                })
                .subscribe();

        // 每轮都要重新登记，否则停止请求作用在上一轮早已结束的订阅上（踩坑点 #9）
        taskManager.setDisposable(params.conversationId(), subscription);
    }

    /** 处理单个流式 chunk：要么是工具调用分片，要么是正文文本片段。 */
    private void processChunk(ChatResponse chunk, RoundState state) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();

        if (output.hasToolCalls()) {
            output.getToolCalls().forEach(state::acceptToolCall);
            return;
        }

        String text = output.getText();
        if (text != null) {
            state.appendText(text);
        }
    }

    /** 一轮流结束后的分支：无工具调用即终局；有工具调用则执行、拼回消息、递归下一轮。 */
    private void finishRound(List<Message> messages, RoundState state, Sinks.Many<AgentStreamEvent> sink,
                             RunnableParams params, AtomicInteger roundCounter) {
        if (state.mode() == RoundMode.TEXT) {
            sink.tryEmitNext(new AgentStreamEvent.Text(state.text()));
            sink.tryEmitNext(new AgentStreamEvent.Complete(params.conversationId()));
            sink.tryEmitComplete();
            // 任务正常跑完，释放单飞占位，让该会话能发起下一轮对话
            taskManager.removeTask(params.conversationId());
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = state.toolCalls();
        // 先把带 tool_calls 的助手消息落进历史，再落工具结果——顺序颠倒模型侧会解析失败
        messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());

        ToolParamInjector paramInjector = new ToolParamInjector(params.toolParams());
        List<ToolResponseMessage.ToolResponse> responses =
                toolCallExecutor.execute(toolCalls, sink, paramInjector);
        messages.add(ToolResponseMessage.builder().responses(responses).build());

        scheduleRound(messages, sink, params, roundCounter);
    }
}
