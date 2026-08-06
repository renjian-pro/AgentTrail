package com.agenttrail.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmJudgeTest {

    private static final GoldenCase CASE = new GoldenCase("case-1", "sql_correctness", "count rentals", "admin",
            null, null, List.of(Map.of("type", "tool_called", "name", "execute_sql")));
    private static final GoldenTaskReport.GoldenObservation OBSERVATION = new GoldenTaskReport.GoldenObservation(
            "case-1", "sql_correctness", true, "", 1, 10, "SELECT COUNT(*) FROM rental",
            "query succeeded, 1 row", List.of("execute_sql"), Map.of());

    @Test
    void parsesAWellFormedJudgeResponse() {
        LlmJudge judge = new LlmJudge(chatModelReturning(
                "{\"accuracy\":5,\"completeness\":4,\"compliance\":5,\"reason\":\"ok\"}"));

        LlmJudge.JudgeScore score = judge.judge(CASE, OBSERVATION);

        assertThat(score.accuracy()).isEqualTo(5);
        assertThat(score.completeness()).isEqualTo(4);
        assertThat(score.reason()).isEqualTo("ok");
    }

    /**
     * 踩坑点 #92：判分调用同样要经过 {@code SynchronousLlmCall}，模型调用失败（含它内部触发的超时）
     * 必须转成 {@code judge()} 已有的 {@code IllegalStateException} 契约，不能让异常类型跟着换了
     * 调用方式而变化——超时本身多快触发、取消语义对不对，由 {@code SynchronousLlmCallTest} 用短
     * 超时锁定，这里只验证"判分调用失败" end-to-end 真的还是走同一条降级路径，不用真的等一次 30s。
     */
    @Test
    void wrapsAModelCallFailureAsTheExistingJudgeContractInsteadOfHangingOrChangingExceptionType() {
        LlmJudge judge = new LlmJudge(failingChatModel());

        assertThatThrownBy(() -> judge.judge(CASE, OBSERVATION)).isInstanceOf(IllegalStateException.class);
    }

    private static ChatModel chatModelReturning(String rawJson) {
        return chatModel(() -> new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content(rawJson).build()))));
    }

    private static ChatModel failingChatModel() {
        return chatModel(() -> {
            throw new IllegalStateException("模型不可用");
        });
    }

    private static ChatModel chatModel(Supplier<ChatResponse> supplier) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return supplier.get();
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("判分走同步调用");
            }
        };
    }
}
