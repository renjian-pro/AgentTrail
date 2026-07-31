package com.agenttrail.loop.structured;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonRepairTest {

    @Test
    void leavesAlreadyValidJsonUntouched() {
        assertThat(JsonRepair.fixJson("{\"name\":\"a\",\"count\":1}"))
                .isEqualTo("{\"name\":\"a\",\"count\":1}");
    }

    @Test
    void stripsMarkdownCodeFence() {
        String repaired = JsonRepair.fixJson("```json\n{\"name\":\"a\"}\n```");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
        assertThat(repaired).isEqualTo("{\"name\":\"a\"}");
    }

    @Test
    void removesLeadingAndTrailingProse() {
        String repaired = JsonRepair.fixJson("这是结果：{\"name\":\"a\"} 以上是分析");

        assertThat(repaired).isEqualTo("{\"name\":\"a\"}");
    }

    @Test
    void fixesTrailingCommas() {
        String repaired = JsonRepair.fixJson("{\"items\":[1,2,],\"name\":\"a\",}");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
    }

    @Test
    void addsQuotesToBareKeys() {
        String repaired = JsonRepair.fixJson("{name:\"a\",count:1}");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
        assertThat(repaired).contains("\"name\"").contains("\"count\"");
    }

    @Test
    void convertsChineseAndStructuralSingleQuotesButPreservesQuotesInsideStrings() {
        String repaired = JsonRepair.fixJson("{'name':'it\\'s a test','note':\"她说：“你好”\"}");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
    }

    @Test
    void replacesUnescapedNewlinesInsideStrings() {
        String repaired = JsonRepair.fixJson("{\"text\":\"line one\nline two\"}");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
    }

    @Test
    void fallsBackToWrappingAsPlainContentWhenUnrepairable() {
        String hopelessInput = "the model just wrote a sentence, not JSON at all";

        String repaired = JsonRepair.fixJson(hopelessInput);

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
        assertThat(repaired).contains(hopelessInput);
    }

    @Test
    void blankInputBecomesAnEmptyObject() {
        assertThat(JsonRepair.fixJson("")).isEqualTo("{}");
        assertThat(JsonRepair.fixJson(null)).isEqualTo("{}");
    }
}
