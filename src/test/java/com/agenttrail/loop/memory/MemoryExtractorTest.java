package com.agenttrail.loop.memory;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** 参照 {@code ContextCompactorTest} 里"额外 LLM 调用可能失败，必须降级"的测试思路。 */
class MemoryExtractorTest {

    @Test
    void extractsAndSavesValidJsonItems() {
        ChatModel model = respondingWith("[{\"type\":\"PROFILE\",\"content\":\"产品经理\"}," +
                "{\"type\":\"PREFERENCE\",\"content\":\"偏好中文回复\"}]");
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "我是产品经理", "好的，记住了");

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::type, MemoryItem::content)
                .containsExactly(
                        tuple(MemoryType.PROFILE, "产品经理"),
                        tuple(MemoryType.PREFERENCE, "偏好中文回复"));
    }

    @Test
    void repairsSlightlyMalformedJsonBeforeParsing() {
        // 尾部多了个逗号，且键名没加引号
        ChatModel model = respondingWith("[{type:\"FACT\",content:\"使用 MySQL 8.0\",}]");
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "我们用 MySQL 8.0", "了解");

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::content).containsExactly("使用 MySQL 8.0");
    }

    @Test
    void emptyArrayExtractsNothing() {
        ChatModel model = respondingWith("[]");
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "今天天气怎么样", "晴天");

        assertThat(store.findByUserId("user-1")).isEmpty();
    }

    @Test
    void skipsItemsWithAnUnknownType() {
        ChatModel model = respondingWith("[{\"type\":\"UNKNOWN\",\"content\":\"忽略我\"}," +
                "{\"type\":\"FACT\",\"content\":\"保留我\"}]");
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "q", "a");

        assertThat(store.findByUserId("user-1")).extracting(MemoryItem::content).containsExactly("保留我");
    }

    @Test
    void doesNotSaveAnythingWhenTheModelCallFails() {
        ChatModel model = failingModel();
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "问题", "回答");

        assertThat(store.findByUserId("user-1")).isEmpty();
    }

    @Test
    void doesNotSaveAnythingWhenTheOutputIsHopelesslyUnrepairable() {
        // 修复兜底会把这段话包成 {"content": "..."}，是个对象而不是数组，解析成 List<ExtractionItem> 必然失败
        ChatModel model = respondingWith("抱歉，我没有更多可以补充的内容了");
        InMemoryMemoryStore store = new InMemoryMemoryStore();

        new MemoryExtractor(model, store).extractAndSave("user-1", "问题", "回答");

        assertThat(store.findByUserId("user-1")).isEmpty();
    }

    @Test
    void doesNothingWhenAnyRequiredArgumentIsMissing() {
        ChatModel model = respondingWith("[{\"type\":\"FACT\",\"content\":\"不该被调用\"}]");
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        MemoryExtractor extractor = new MemoryExtractor(model, store);

        extractor.extractAndSave(null, "问题", "回答");
        extractor.extractAndSave("user-1", null, "回答");
        extractor.extractAndSave("user-1", "问题", null);
        extractor.extractAndSave("user-1", "问题", "");

        assertThat(store.findByUserId("user-1")).isEmpty();
    }

    private static ChatModel respondingWith(String rawOutput) {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                return text(rawOutput);
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("提取走同步调用");
            }
        };
    }

    private static ChatModel failingModel() {
        return new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("模型不可用");
            }

            @Override
            public Flux<ChatResponse> stream(Prompt prompt) {
                throw new UnsupportedOperationException("提取走同步调用");
            }
        };
    }
}
