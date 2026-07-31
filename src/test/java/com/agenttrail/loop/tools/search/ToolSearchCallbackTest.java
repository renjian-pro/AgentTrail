package com.agenttrail.loop.tools.search;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HYBRID 检索的核心约束：关键词优先，只有零命中时才退化到 LLM——不是两条路径都跑一遍。
 */
class ToolSearchCallbackTest {

    private static ToolCallback tool(String name, String description) {
        return new RecordingToolCallback(name, description, "ok");
    }

    private static ToolSearchCallback callbackOf(ToolSearchConfig config, ChatModel chatModel,
                                                 Set<String> discovered, ToolCallback... tools) {
        Map<String, ToolCallback> byName = List.of(tools).stream()
                .collect(java.util.stream.Collectors.toMap(t -> t.getToolDefinition().name(), t -> t));
        List<ToolIndexEntry> index = ToolIndexEntry.buildIndex(byName);
        return new ToolSearchCallback(config, index, ToolIndexEntry.indexByName(index), chatModel, discovered);
    }

    @Test
    void keywordHitNeverTriggersAnLlmCall() {
        CountingChatModel chatModel = new CountingChatModel(list());
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchCallback callback = callbackOf(ToolSearchConfig.defaults(), chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        String result = callback.call("{\"query\":\"天气\"}");

        assertThat(chatModel.callCount()).isZero();
        assertThat(result).contains("getWeather");
        assertThat(discovered).containsExactly("getWeather");
    }

    @Test
    void fallsBackToLlmOnlyWhenKeywordSearchFindsNothing() {
        CountingChatModel chatModel = new CountingChatModel(list("getWeather"));
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchCallback callback = callbackOf(ToolSearchConfig.defaults(), chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        String result = callback.call("{\"query\":\"帮我订一张明天去上海的机票\"}");

        assertThat(chatModel.callCount()).isEqualTo(1);
        assertThat(result).contains("getWeather");
        assertThat(discovered).containsExactly("getWeather");
    }

    @Test
    void keywordModeNeverFallsBackToLlmEvenOnZeroHits() {
        CountingChatModel chatModel = new CountingChatModel(list("getWeather"));
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchConfig keywordOnly = new ToolSearchConfig(ToolSearchConfig.Mode.KEYWORD, 5);
        ToolSearchCallback callback = callbackOf(keywordOnly, chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        String result = callback.call("{\"query\":\"帮我订一张明天去上海的机票\"}");

        assertThat(chatModel.callCount()).isZero();
        assertThat(discovered).isEmpty();
        assertThat(result).doesNotContain("getWeather");
    }

    @Test
    void llmModeSkipsKeywordScoringAndAlwaysAsksTheModel() {
        CountingChatModel chatModel = new CountingChatModel(list("getWeather"));
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchConfig llmOnly = new ToolSearchConfig(ToolSearchConfig.Mode.LLM, 5);
        ToolSearchCallback callback = callbackOf(llmOnly, chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        // 即便查询本身和名称精确匹配，LLM 模式也不能走关键词打分，必须真的问一次模型
        callback.call("{\"query\":\"getWeather\"}");

        assertThat(chatModel.callCount()).isEqualTo(1);
    }

    @Test
    void ignoresHallucinatedToolNamesReturnedByTheLlm() {
        CountingChatModel chatModel = new CountingChatModel(list("getWeather", "sendEmailThatDoesNotExist"));
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchCallback callback = callbackOf(ToolSearchConfig.defaults(), chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        callback.call("{\"query\":\"完全无关的查询\"}");

        assertThat(discovered).containsExactly("getWeather");
    }

    @Test
    void returnsAReadableMessageInsteadOfCrashingWhenNothingMatchesAtAll() {
        CountingChatModel chatModel = new CountingChatModel(list());
        Set<String> discovered = ConcurrentHashMap.newKeySet();
        ToolSearchCallback callback = callbackOf(ToolSearchConfig.defaults(), chatModel, discovered,
                tool("getWeather", "查询天气预报"));

        String result = callback.call("{\"query\":\"完全无关的查询\"}");

        assertThat(discovered).isEmpty();
        assertThat(result).isNotBlank();
    }

    @Test
    void blankQueryAsksTheCallerToSupplyOneInsteadOfSearchingEmptyHanded() {
        CountingChatModel chatModel = new CountingChatModel(list());
        ToolSearchCallback callback = callbackOf(ToolSearchConfig.defaults(), chatModel,
                ConcurrentHashMap.newKeySet(), tool("getWeather", "查询天气预报"));

        String result = callback.call("{}");

        assertThat(chatModel.callCount()).isZero();
        assertThat(result).isNotBlank();
    }

    private static String list(String... names) {
        return "[" + String.join(",", List.of(names).stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }

    /** 回放固定 JSON 数组文本，并记录被调用的次数。 */
    private static final class CountingChatModel implements ChatModel {

        private final String responseJson;
        private final AtomicInteger calls = new AtomicInteger();

        CountingChatModel(String responseJson) {
            this.responseJson = responseJson;
        }

        int callCount() {
            return calls.get();
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(responseJson).build())));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            throw new UnsupportedOperationException("ToolSearch 的 LLM 检索走同步调用");
        }
    }
}
