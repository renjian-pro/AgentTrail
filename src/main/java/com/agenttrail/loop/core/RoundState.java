package com.agenttrail.loop.core;

import com.agenttrail.loop.stage.ThinkTagParser;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.List;

/**
 * 单轮流式响应的累积状态。
 *
 * <p>一轮到底是"文本轮"还是"工具调用轮"，只有等整个流结束才能确定：模型可能先吐几个字，
 * 然后才决定调工具。所以 {@link #mode} 初始为 {@link RoundMode#TEXT}，一旦出现工具调用就翻转，
 * 且在流结束前不做任何动作——真正的分支判断在 {@code AgentLoopExecutor#finishRound}。
 *
 * <p>正文和思考过程分开累积：正文最终会作为答案落库，思考过程只用于实时展示，
 * 混在一起会让历史记录里出现大段自言自语。
 *
 * <p>按设计是单线程的：一轮一个实例，只被该轮的 chunk 处理器写入。
 */
class RoundState {

    private final StringBuilder textBuffer = new StringBuilder();
    private final StringBuilder reasoningBuffer = new StringBuilder();
    private final ToolCallAccumulator toolCalls = new ToolCallAccumulator();

    /** 跨 chunk 的 think 标签解析状态，一轮一份。 */
    private final ThinkTagParser thinkTagParser = new ThinkTagParser();

    /** 本轮发起时刻，供 TraceAudit（issue #17）计算本轮耗时用。 */
    private final long startMillis = System.currentTimeMillis();

    /** Token 消耗，取模型响应 metadata 里的 usage；模型未报告时保持 -1，不能悄悄当 0 记录进审计。 */
    private long promptTokens = -1;
    private long completionTokens = -1;

    private RoundMode mode = RoundMode.TEXT;

    void appendText(String text) {
        textBuffer.append(text);
    }

    void appendReasoning(String reasoning) {
        reasoningBuffer.append(reasoning);
    }

    List<ThinkTagParser.Segment> parseThinkTags(String chunk) {
        return thinkTagParser.parse(chunk);
    }

    /** 流结束时取出解析器还攒着的内容。 */
    List<ThinkTagParser.Segment> flushThinkTags() {
        return thinkTagParser.flush();
    }

    /** 收下一个流式工具调用分片，并把本轮标记为工具调用轮。 */
    void acceptToolCall(ToolCall toolCall) {
        mode = RoundMode.TOOL_CALL;
        toolCalls.accept(toolCall);
    }

    /** 模型响应 metadata 里带了 usage 的 chunk 到达时更新——有些厂商只在最后一个 chunk 里给。 */
    void acceptUsage(long promptTokens, long completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
    }

    RoundMode mode() {
        return mode;
    }

    long startMillis() {
        return startMillis;
    }

    long promptTokens() {
        return promptTokens;
    }

    long completionTokens() {
        return completionTokens;
    }

    String text() {
        return textBuffer.toString();
    }

    String reasoning() {
        return reasoningBuffer.toString();
    }

    /** 重组完成的工具调用列表，顺序为模型首次提及的顺序。 */
    List<ToolCall> toolCalls() {
        return toolCalls.toList();
    }
}
