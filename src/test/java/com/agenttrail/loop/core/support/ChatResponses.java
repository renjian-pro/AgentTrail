package com.agenttrail.loop.core.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/** 构造测试用流式 chunk 的工厂方法，让测试用例读起来接近"厂商实际怎么下发"。 */
public final class ChatResponses {

    private ChatResponses() {
    }

    /** 一个纯文本 chunk。 */
    public static ChatResponse text(String content) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(content).build())));
    }

    /** 工具调用的首个 chunk：带 id、name 和参数 JSON 的开头一段。 */
    public static ChatResponse toolCall(String id, String name, String argumentsFragment) {
        return chunkOf(call(id, name, argumentsFragment));
    }

    /** 工具调用的后续 chunk：只重复 id 和参数的下一段，name 为 null。 */
    public static ChatResponse toolCallFragment(String id, String argumentsFragment) {
        return chunkOf(call(id, null, argumentsFragment));
    }

    /** 一个 chunk 里同时带多个工具调用——厂商发起并行调用时的形态。 */
    public static ChatResponse toolCalls(AssistantMessage.ToolCall... calls) {
        AssistantMessage message = AssistantMessage.builder().toolCalls(List.of(calls)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }

    public static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ChatResponse chunkOf(AssistantMessage.ToolCall toolCall) {
        AssistantMessage message = AssistantMessage.builder().toolCalls(List.of(toolCall)).build();
        return new ChatResponse(List.of(new Generation(message)));
    }
}
