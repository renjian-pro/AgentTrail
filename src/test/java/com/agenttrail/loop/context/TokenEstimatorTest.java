package com.agenttrail.loop.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 估算不接外部分词库，靠中英文差异化的字符比率算——够用且零依赖。
 * 它的准确度不需要很高：只是用来决定"要不要触发压缩"这个粗粒度判断。
 */
class TokenEstimatorTest {

    @Test
    void countsNothingForAnEmptyConversation() {
        assertThat(TokenEstimator.estimateTokens(List.of())).isZero();
        assertThat(TokenEstimator.estimateTokens(null)).isZero();
    }

    /** 中文字符信息密度高，同样字数占的 token 明显多于英文——比率必须区分，否则中文场景会严重低估。 */
    @Test
    void ratesCjkCharactersHeavierThanAsciiOnes() {
        int cjkTokens = TokenEstimator.estimateTokens(List.of(new UserMessage("一二三四五六七八九十一二")));
        int asciiTokens = TokenEstimator.estimateTokens(List.of(new UserMessage("abcdefghijkl")));

        assertThat(cjkTokens).isGreaterThan(asciiTokens);
    }

    @Test
    void growsWithConversationLength() {
        int shortConversation = TokenEstimator.estimateTokens(List.of(new UserMessage("hello")));
        int longConversation = TokenEstimator.estimateTokens(List.of(
                new UserMessage("hello"),
                new UserMessage("hello".repeat(100))));

        assertThat(longConversation).isGreaterThan(shortConversation);
    }

    /** 工具调用的参数和返回值往往是上下文里最大的一块，漏算它们会导致压缩迟迟不触发。 */
    @Test
    void includesToolCallArgumentsAndToolResponsesInTheEstimate() {
        Message toolCall = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "executeSql", "{\"sql\":\"" + "x".repeat(400) + "\"}")))
                .build();
        Message toolResponse = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "executeSql", "y".repeat(400))))
                .build();

        assertThat(TokenEstimator.estimateTokens(List.of(toolCall))).isGreaterThan(50);
        assertThat(TokenEstimator.estimateTokens(List.of(toolResponse))).isGreaterThan(50);
    }
}
