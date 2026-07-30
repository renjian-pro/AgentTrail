package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.stage.ThinkTagParser;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;

/**
 * 把各厂商交付"思考过程"的不同方式，统一分流成 Thinking / Text 两种事件。
 *
 * <p>三种模式的差异全部收敛在这里，循环本身不需要知道当前对接的是哪家模型：
 * <ul>
 *   <li>{@link ThinkingMode#REASONING_CONTENT}：思考在独立字段里，content 天然干净
 *   <li>{@link ThinkingMode#THINK_TAG}：两者混在 content 里，得靠标签拆
 *   <li>{@link ThinkingMode#DISABLED}：不展示思考，但标签内容也不能漏进正文
 * </ul>
 */
class ThinkingModeProcessor {

    /** 不同厂商/SDK 版本对同一个字段的两种拼写。 */
    private static final List<String> REASONING_KEYS = List.of("reasoningContent", "reasoning_content");

    private final ThinkingMode mode;

    ThinkingModeProcessor(ThinkingMode mode) {
        this.mode = mode;
    }

    /** 处理 content 字段里的一个文本 chunk。 */
    void processText(String text, RoundState state, Sinks.Many<AgentStreamEvent> sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (mode == ThinkingMode.REASONING_CONTENT) {
            // content 里本来就没有思考内容，省掉一次解析
            state.appendText(text);
            sink.tryEmitNext(new AgentStreamEvent.Text(text));
            return;
        }
        emitSegments(state.parseThinkTags(text), state, sink);
    }

    /**
     * 处理独立字段里的思考内容。
     *
     * <p>思考内容**不进正文缓冲区**——正文缓冲区的内容会作为最终答案落库，
     * 混进推理过程会让历史里出现大段自言自语。
     */
    void processReasoning(AssistantMessage message, RoundState state, Sinks.Many<AgentStreamEvent> sink) {
        if (mode != ThinkingMode.REASONING_CONTENT) {
            return;
        }
        String reasoning = reasoningOf(message);
        if (reasoning != null && !reasoning.isEmpty()) {
            state.appendReasoning(reasoning);
            sink.tryEmitNext(new AgentStreamEvent.Thinking(reasoning));
        }
    }

    /** 一轮流结束：把标签解析器还攒着的尾巴吐出来，否则最后几个字会丢。 */
    void finishRound(RoundState state, Sinks.Many<AgentStreamEvent> sink) {
        if (mode != ThinkingMode.REASONING_CONTENT) {
            emitSegments(state.flushThinkTags(), state, sink);
        }
    }

    private void emitSegments(List<ThinkTagParser.Segment> segments, RoundState state,
                              Sinks.Many<AgentStreamEvent> sink) {
        for (ThinkTagParser.Segment segment : segments) {
            if (segment.thinking()) {
                state.appendReasoning(segment.content());
                // DISABLED 模式下思考内容照样被剥离出来，只是不投递事件
                if (mode == ThinkingMode.THINK_TAG) {
                    sink.tryEmitNext(new AgentStreamEvent.Thinking(segment.content()));
                }
            } else {
                state.appendText(segment.content());
                sink.tryEmitNext(new AgentStreamEvent.Text(segment.content()));
            }
        }
    }

    /**
     * 从消息里取出思考内容。
     *
     * <p>只走 metadata，不做反射兜底：反射依赖具体 ChatModel 实现暴露的方法名，
     * 换个厂商就失效，属于隐式耦合。真需要时应该由该厂商的 ChatModel 装饰器
     * 把字段规范化进 metadata，而不是让本类去猜实现类长什么样。
     */
    private String reasoningOf(AssistantMessage message) {
        Map<String, Object> metadata = message.getMetadata();
        if (metadata == null) {
            return null;
        }
        for (String key : REASONING_KEYS) {
            if (metadata.get(key) instanceof String reasoning && !reasoning.isEmpty()) {
                return reasoning;
            }
        }
        return null;
    }
}
