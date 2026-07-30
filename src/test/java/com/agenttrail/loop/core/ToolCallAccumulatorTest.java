package com.agenttrail.loop.core;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCallAccumulatorTest {

    @Test
    void joinsArgumentFragmentsThatShareAToolCallId() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();

        accumulator.accept(new ToolCall("call-1", "function", "echo", "{\"text\":"));
        accumulator.accept(new ToolCall("call-1", "function", null, "\"ping\"}"));

        assertThat(accumulator.toList()).singleElement().satisfies(call -> {
            assertThat(call.id()).isEqualTo("call-1");
            assertThat(call.name()).isEqualTo("echo");
            assertThat(call.arguments()).isEqualTo("{\"text\":\"ping\"}");
        });
    }

    @Test
    void keepsDistinctToolCallsSeparateInFirstSeenOrder() {
        ToolCallAccumulator accumulator = new ToolCallAccumulator();

        accumulator.accept(new ToolCall("call-1", "function", "first", "{\"a\":"));
        accumulator.accept(new ToolCall("call-2", "function", "second", "{\"b\":"));
        accumulator.accept(new ToolCall("call-1", "function", null, "1}"));
        accumulator.accept(new ToolCall("call-2", "function", null, "2}"));

        assertThat(accumulator.toList()).extracting(ToolCall::id).containsExactly("call-1", "call-2");
        assertThat(accumulator.toList()).extracting(ToolCall::arguments)
                .containsExactly("{\"a\":1}", "{\"b\":2}");
    }

    /**
     * Fuzz test for pitfall #1: whatever way the provider happens to chop the argument JSON,
     * concatenating fragments by id must reproduce the original string byte for byte.
     */
    @Test
    void reassemblesAnyRandomSplitOfTheSameArgumentJson() {
        String original = "{\"query\":\"select * from film where title like '%love%'\",\"limit\":25}";
        Random random = new Random(20260730L);

        for (int attempt = 0; attempt < 200; attempt++) {
            ToolCallAccumulator accumulator = new ToolCallAccumulator();
            List<String> fragments = randomSplit(original, random);

            for (int i = 0; i < fragments.size(); i++) {
                String name = (i == 0) ? "executeSql" : null;
                accumulator.accept(new ToolCall("call-1", "function", name, fragments.get(i)));
            }

            assertThat(accumulator.toList()).singleElement().satisfies(call -> {
                assertThat(call.arguments()).isEqualTo(original);
                assertThat(call.name()).isEqualTo("executeSql");
            });
        }
    }

    private List<String> randomSplit(String value, Random random) {
        List<String> fragments = new ArrayList<>();
        int cursor = 0;
        while (cursor < value.length()) {
            int size = 1 + random.nextInt(Math.min(8, value.length() - cursor));
            fragments.add(value.substring(cursor, cursor + size));
            cursor += size;
        }
        return fragments;
    }
}
