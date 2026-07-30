package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.ThinkingMode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三种思考模式的分流。同一段"带思考的输出"，在不同厂商那里长得完全不一样，
 * 这一层的职责就是把差异吃掉，对上游只暴露 Thinking / Text 两种事件。
 */
class ThinkingModeProcessorTest {

    private final Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();

    /** DISABLED：不产出思考事件；think 标签内容也不该泄漏到正文里。 */
    @Test
    void emitsOnlyTextWhenThinkingIsDisabled() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.DISABLED);
        RoundState state = new RoundState();

        processor.processText("正文A<think>推理</think>正文B", state, sink);

        assertThat(eventsSoFar()).containsExactly(
                new AgentStreamEvent.Text("正文A"),
                new AgentStreamEvent.Text("正文B"));
    }

    /** THINK_TAG：标签内容走 Thinking 通道，标签外走 Text 通道。 */
    @Test
    void splitsTaggedThinkingFromTextWhenUsingThinkTags() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.THINK_TAG);
        RoundState state = new RoundState();

        processor.processText("正文A<think>推理</think>正文B", state, sink);

        assertThat(eventsSoFar()).containsExactly(
                new AgentStreamEvent.Text("正文A"),
                new AgentStreamEvent.Thinking("推理"),
                new AgentStreamEvent.Text("正文B"));
    }

    /** THINK_TAG 模式下，标签被切断在 chunk 边界上也不能把半截标签当正文吐出去。 */
    @Test
    void keepsAHalfArrivedTagOutOfTheTextChannel() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.THINK_TAG);
        RoundState state = new RoundState();

        processor.processText("正文<thi", state, sink);
        processor.processText("nk>推理</think>结尾", state, sink);

        List<AgentStreamEvent> events = eventsSoFar();
        assertThat(textOf(events)).isEqualTo("正文结尾");
        assertThat(thinkingOf(events)).isEqualTo("推理");
    }

    /** REASONING_CONTENT：content 字段本来就是干净正文，不需要再解析标签。 */
    @Test
    void passesContentStraightThroughWhenReasoningComesSeparately() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.REASONING_CONTENT);
        RoundState state = new RoundState();

        processor.processText("正文", state, sink);

        assertThat(eventsSoFar()).containsExactly(new AgentStreamEvent.Text("正文"));
    }

    /** 独立字段里的思考内容走 Thinking 通道，且不会混进正文缓冲区。 */
    @Test
    void emitsSeparatelyDeliveredReasoningAsThinking() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.REASONING_CONTENT);
        RoundState state = new RoundState();
        AssistantMessage message = AssistantMessage.builder()
                .content("正文")
                .properties(Map.of("reasoningContent", "推理"))
                .build();

        processor.processReasoning(message, state, sink);

        assertThat(eventsSoFar()).containsExactly(new AgentStreamEvent.Thinking("推理"));
        assertThat(state.text()).doesNotContain("推理");
    }

    /** 两种字段命名都要认——不同厂商/不同 SDK 版本用的键名不一致。 */
    @Test
    void acceptsEitherSpellingOfTheReasoningMetadataKey() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.REASONING_CONTENT);
        AssistantMessage snakeCase = AssistantMessage.builder()
                .content("正文")
                .properties(Map.of("reasoning_content", "推理"))
                .build();

        processor.processReasoning(snakeCase, new RoundState(), sink);

        assertThat(eventsSoFar()).containsExactly(new AgentStreamEvent.Thinking("推理"));
    }

    /** 非 REASONING_CONTENT 模式下不去碰独立字段，避免重复投递。 */
    @Test
    void ignoresReasoningMetadataInOtherModes() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.THINK_TAG);
        AssistantMessage message = AssistantMessage.builder()
                .content("正文")
                .properties(Map.of("reasoningContent", "推理"))
                .build();

        processor.processReasoning(message, new RoundState(), sink);

        assertThat(eventsSoFar()).isEmpty();
    }

    /** 一轮结束时要把攒住的尾巴吐出来，否则最后几个字会丢。 */
    @Test
    void flushesAnyTextHeldBackWhenTheRoundEnds() {
        ThinkingModeProcessor processor = new ThinkingModeProcessor(ThinkingMode.THINK_TAG);
        RoundState state = new RoundState();
        processor.processText("正文<", state, sink);

        processor.finishRound(state, sink);

        assertThat(textOf(eventsSoFar())).isEqualTo("正文<");
    }

    private List<AgentStreamEvent> eventsSoFar() {
        sink.tryEmitComplete();
        return sink.asFlux().collectList().block(Duration.ofSeconds(1));
    }

    private static String textOf(List<AgentStreamEvent> events) {
        return events.stream()
                .filter(AgentStreamEvent.Text.class::isInstance)
                .map(event -> ((AgentStreamEvent.Text) event).content())
                .reduce("", String::concat);
    }

    private static String thinkingOf(List<AgentStreamEvent> events) {
        return events.stream()
                .filter(AgentStreamEvent.Thinking.class::isInstance)
                .map(event -> ((AgentStreamEvent.Thinking) event).content())
                .reduce("", String::concat);
    }
}
