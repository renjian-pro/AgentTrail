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
    void repairsUnescapedQuotesInsideStringValues() {
        // 模型常见瑕疵：在 bullets 数组的字符串值里直接写英文双引号"丑角"、
        // "积极热情勤奋"，整个 JSON 会因此被截断。fixJson 应当把这种"字符串内"的
        // 裸引号替换成左中文引号，让 JSON 重新能解析，且内容语义保留
        String broken = "{\n"
                + "  \"deckTitle\": \"小丑入职秀\",\n"
                + "  \"bullets\": [\n"
                + "    \"戏剧原型中的\"丑角\"跳脱常规\",\n"
                + "    \"打破\"积极热情勤奋\"的套路\"\n"
                + "  ]\n"
                + "}";

        String repaired = JsonRepair.fixJson(broken);

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
        // 丑角、积极热情勤奋 这两组引号应当被替换为左中文引号（U+201C）
        assertThat(repaired).contains("“丑角”").contains("“积极热情勤奋”");
    }

    @Test
    void repairsUnescapedQuoteFollowedByStructuralCharacter() {
        // 字符串值的引号如果后面紧跟 , } ] : ，那它就是字符串结束，不动它
        String repaired = JsonRepair.fixJson("{\"name\":\"a\",\"value\":\"b\"}");

        assertThat(JsonRepair.isValidJson(repaired)).isTrue();
        assertThat(repaired).isEqualTo("{\"name\":\"a\",\"value\":\"b\"}");
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
