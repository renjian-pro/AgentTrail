package com.agenttrail.evaluation;

import com.agenttrail.loop.prompt.PromptRegistry;
import com.agenttrail.loop.core.SynchronousLlmCall;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Structured LLM-as-Judge. The invoker must return the JSON object described by
 * {@link #RESPONSE_SCHEMA}; free-form prose is rejected instead of being heuristically scored.
 */
public final class LlmJudge {
    /** 提示词正文外置在 {@code resources/prompts/}（issue #100）：改一句不用动代码，
     *  且每一版都有可写进 trace 的 {@code id@version#hash} 标识，Golden 分数变化才归因得了。 */
    private static final PromptRegistry PROMPTS = PromptRegistry.shared();

    public static final String RESPONSE_SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["accuracy","completeness","compliance"],"properties":{
              "accuracy":{"type":"integer","minimum":0,"maximum":5},
              "completeness":{"type":"integer","minimum":0,"maximum":5},
              "compliance":{"type":"integer","minimum":0,"maximum":5},
              "reason":{"type":"string"}
            }}
            """;

    private static final String SYSTEM_PROMPT = PROMPTS.text("runtime.llm_judge").formatted(RESPONSE_SCHEMA);

    private final Function<String, String> structuredInvoker;
    private final ObjectMapper objectMapper;

    public LlmJudge(Function<String, String> structuredInvoker) {
        this(structuredInvoker, new ObjectMapper());
    }

    public LlmJudge(Function<String, String> structuredInvoker, ObjectMapper objectMapper) {
        this.structuredInvoker = structuredInvoker;
        this.objectMapper = objectMapper;
    }

    public LlmJudge(ChatModel chatModel) {
        // 不直接 this(lambda ->...)：Function<String,String> 和 ChatModel 都只有一个抽象方法，
        // 两个构造函数重载对同一个 lambda 都"形状匹配"，javac 在参数形状阶段就判为二义性调用，
        // 不会等到检查 lambda 方法体的返回类型是否兼容——先转成具体类型的局部变量再传，绕开这个坑。
        this(toStructuredInvoker(chatModel));
    }

    private static Function<String, String> toStructuredInvoker(ChatModel chatModel) {
        return prompt -> {
            ChatResponse response = SynchronousLlmCall.call(chatModel, new Prompt(List.of(
                    new SystemMessage(SYSTEM_PROMPT), new UserMessage(prompt))));
            return response.getResult().getOutput().getText();
        };
    }

    public JudgeScore judge(GoldenCase testCase, GoldenTaskReport.GoldenObservation observation) {
        String request = "CASE_ID=" + testCase.id() + "\nQUESTION=" + testCase.question()
                + "\nEXPECTED=" + String.valueOf(testCase.expected())
                + "\nACTUAL_RESULT=" + observation.actualResult()
                + "\nACTUAL_SQL=" + observation.actualSql()
                + "\nMETRICS=" + observation.metrics();
        try {
            JsonNode node = objectMapper.readTree(structuredInvoker.apply(request));
            validate(node, "accuracy");
            validate(node, "completeness");
            validate(node, "compliance");
            return new JudgeScore(node.get("accuracy").intValue(), node.get("completeness").intValue(),
                    node.get("compliance").intValue(), node.path("reason").asText(""));
        } catch (Exception failure) {
            throw new IllegalStateException("Structured LLM judge response is invalid", failure);
        }
    }

    public static JudgeVariance variance(List<JudgeScore> scores) {
        if (scores == null || scores.isEmpty()) return new JudgeVariance(0, 0, 0);
        return new JudgeVariance(range(scores, JudgeScore::accuracy), range(scores, JudgeScore::completeness),
                range(scores, JudgeScore::compliance));
    }

    private static void validate(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || !value.isIntegralNumber() || value.intValue() < 0 || value.intValue() > 5) {
            throw new IllegalArgumentException("field " + field + " must be an integer between 0 and 5");
        }
    }

    private static double range(List<JudgeScore> scores, Function<JudgeScore, Integer> value) {
        int min = scores.stream().map(value).min(Integer::compareTo).orElse(0);
        int max = scores.stream().map(value).max(Integer::compareTo).orElse(0);
        return max - min;
    }

    public record JudgeScore(int accuracy, int completeness, int compliance, String reason) { }

    public record JudgeVariance(double accuracy, double completeness, double compliance) {
        public boolean within(double threshold) {
            return accuracy <= threshold && completeness <= threshold && compliance <= threshold;
        }
    }
}
