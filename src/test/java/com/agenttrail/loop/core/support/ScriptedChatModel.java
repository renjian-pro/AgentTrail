package com.agenttrail.loop.core.support;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * {@link ChatModel} 的测试替身：把预设好的响应按轮回放，一次 {@link #stream(Prompt)} 消费一轮。
 *
 * <p>这是流式 Agent 能做确定性单测的唯一手段——真实模型每次输出都不一样，没法断言。
 * 每轮的 chunk 列表由测试给定，因此"参数被拆成几片、怎么拆"这种流式细节可以精确构造。
 *
 * <p>同时记录每轮实际发出去的消息和工具清单，让测试能反过来断言循环"喂给模型的是什么"，
 * 比如验证工具结果确实拼回了历史、ToolSearch 发现的工具确实在下一轮出现了。
 */
public class ScriptedChatModel implements ChatModel {

    private final Deque<List<ChatResponse>> scriptedRounds;
    private final List<List<Message>> recordedMessages = new ArrayList<>();
    private final List<List<String>> recordedToolNames = new ArrayList<>();
    private final List<ChatOptions> recordedOptions = new ArrayList<>();
    private ChatOptions defaultOptions;

    /** @param rounds 每个参数是一轮，轮内是该轮按序下发的 chunk 列表 */
    @SafeVarargs
    public ScriptedChatModel(List<ChatResponse>... rounds) {
        this.scriptedRounds = new ArrayDeque<>(List.of(rounds));
    }

    /**
     * 模拟"这家厂商的 ChatModel 要求用自己的具体 options 子类型"——
     * 验证 {@code LlmInvoker} 不会用一个通用类型的 options 覆盖掉它（会导致下游 ClassCastException）。
     */
    public ScriptedChatModel withDefaultOptions(ChatOptions options) {
        this.defaultOptions = options;
        return this;
    }

    @Override
    public ChatOptions getOptions() {
        return defaultOptions;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        throw new UnsupportedOperationException("ScriptedChatModel 只支持流式调用");
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        recordedMessages.add(List.copyOf(prompt.getInstructions()));
        recordedToolNames.add(toolNamesOf(prompt));
        recordedOptions.add(prompt.getOptions());
        if (scriptedRounds.isEmpty()) {
            throw new IllegalStateException("ScriptedChatModel 预设的轮次已用尽——循环跑的轮数超出了测试预期");
        }
        return Flux.fromIterable(scriptedRounds.poll());
    }

    private List<String> toolNamesOf(Prompt prompt) {
        if (prompt.getOptions() instanceof ToolCallingChatOptions options) {
            return options.getToolCallbacks().stream()
                    .map(ToolCallback::getToolDefinition)
                    .map(definition -> definition.name())
                    .toList();
        }
        return List.of();
    }

    /** 实际发生了多少轮模型调用。 */
    public int roundCount() {
        return recordedMessages.size();
    }

    /** 第 {@code index} 轮发给模型的完整消息列表（含历史）。 */
    public List<Message> messagesAtRound(int index) {
        return recordedMessages.get(index);
    }

    /** 第 {@code index} 轮实际挂给模型的工具名清单。 */
    public List<String> toolNamesAtRound(int index) {
        return recordedToolNames.get(index);
    }

    /** 第 {@code index} 轮实际发给模型的 options——用于断言具体类型有没有被通用实现覆盖掉。 */
    public ChatOptions optionsAtRound(int index) {
        return recordedOptions.get(index);
    }
}
