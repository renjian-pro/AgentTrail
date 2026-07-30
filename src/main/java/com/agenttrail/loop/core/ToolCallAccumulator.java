package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 重组被流式拆散的工具调用（踩坑点 #1）。
 *
 * <p>厂商会把一次逻辑上的工具调用拆成多个 chunk 下发：第一个 chunk 带 {@code id}/{@code name}/
 * {@code type} 和参数 JSON 的开头一段，之后每个 chunk 只重复同一个 {@code id} 加上参数的下一段。
 * 这里按 id 匹配、纯字符串拼接，中间刻意不做任何 JSON 解析——因为半截参数必然不是合法 JSON，
 * 增量校验只会产生假警报。真正的校验放在整轮结束后，由 {@link ToolCallExecutor} 统一做。
 *
 * <p>保留插入顺序，这样后续拼工具结果时能按模型请求的原始顺序回填——OpenAI 形状的协议要求
 * tool 响应与 tool_call 一一对应，顺序错了模型侧会错位关联。
 *
 * <p>非线程安全：一个实例属于一个 {@link RoundState}，只被该轮的 chunk 处理器写入。
 */
class ToolCallAccumulator {

    /** 按工具调用 id 索引；用 {@link LinkedHashMap} 保住首次出现的顺序。 */
    private final Map<String, ToolCall> callsById = new LinkedHashMap<>();

    /**
     * 把一个流式分片折叠进它所属的工具调用里，首次出现时创建。
     *
     * <p>只有第一个分片带 {@code name} 和 {@code type}，后续分片这两个字段是 null，
     * 因此两者都取"最先提供该字段的那个分片"的值。
     */
    void accept(ToolCall incoming) {
        callsById.merge(incoming.id(), incoming, ToolCallAccumulator::join);
    }

    private static ToolCall join(ToolCall existing, ToolCall incoming) {
        return new ToolCall(
                existing.id(),
                firstNonNull(existing.type(), incoming.type()),
                firstNonNull(existing.name(), incoming.name()),
                nullToEmpty(existing.arguments()) + nullToEmpty(incoming.arguments()));
    }

    /** 目前已重组出的工具调用，顺序为模型首次提及的顺序。 */
    List<ToolCall> toList() {
        return List.copyOf(callsById.values());
    }

    boolean isEmpty() {
        return callsById.isEmpty();
    }

    private static String firstNonNull(String preferred, String fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }
}
