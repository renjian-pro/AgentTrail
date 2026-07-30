package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

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
    private final List<ToolCallback> tools;
    private final int maxRounds;

    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        this.llmInvoker = new LlmInvoker(chatModel);
        this.toolCallExecutor = new ToolCallExecutor(tools);
        this.tools = tools;
        this.maxRounds = maxRounds;
    }

    /**
     * 发起一次完整的多轮推理，事件以流的形式实时推给调用方。
     *
     * @param question 用户本轮提问
     * @param params   运行时参数（会话 id、用户 id）
     */
    public Flux<AgentStreamEvent> stream(String question, RunnableParams params) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(question));

        scheduleRound(messages, sink, params);
        return sink.asFlux();
    }

    /**
     * 调度一轮：发起流式请求，边收边攒，流结束后再决定"收尾"还是"执行工具并进入下一轮"。
     *
     * <p>之所以要等整个流结束才决策，是因为一轮的性质（文本轮 / 工具调用轮）在流跑完之前
     * 是不确定的，详见 {@link RoundState}。
     */
    private void scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, RunnableParams params) {
        RoundState state = new RoundState();

        llmInvoker.streamRound(messages, tools)
                .doOnNext(chunk -> processChunk(chunk, state))
                .doOnComplete(() -> finishRound(messages, state, sink, params))
                .doOnError(err -> {
                    sink.tryEmitNext(new AgentStreamEvent.Error("LLM_CALL_FAILED", err.getMessage()));
                    sink.tryEmitComplete();
                })
                .subscribe();
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
    private void finishRound(List<Message> messages, RoundState state,
                             Sinks.Many<AgentStreamEvent> sink, RunnableParams params) {
        if (state.mode() == RoundMode.TEXT) {
            sink.tryEmitNext(new AgentStreamEvent.Text(state.text()));
            sink.tryEmitNext(new AgentStreamEvent.Complete(params.conversationId()));
            sink.tryEmitComplete();
            return;
        }

        List<AssistantMessage.ToolCall> toolCalls = state.toolCalls();
        // 先把带 tool_calls 的助手消息落进历史，再落工具结果——顺序颠倒模型侧会解析失败
        messages.add(AssistantMessage.builder().toolCalls(toolCalls).build());

        List<ToolResponseMessage.ToolResponse> responses = toolCallExecutor.execute(toolCalls, sink);
        messages.add(ToolResponseMessage.builder().responses(responses).build());

        scheduleRound(messages, sink, params);
    }
}
