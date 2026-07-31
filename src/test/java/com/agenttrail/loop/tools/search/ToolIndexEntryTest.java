package com.agenttrail.loop.tools.search;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ToolIndexEntryTest {

    private static ToolCallback tool(String name, String description) {
        return new RecordingToolCallback(name, description, "ok");
    }

    @Test
    void splitsCamelCaseAndSnakeCaseNamesIntoLowercaseTokens() {
        List<ToolIndexEntry> index = ToolIndexEntry.buildIndex(
                Map.of("getWeather", tool("getWeather", "查询天气")));

        assertThat(index.get(0).nameTokens()).containsExactly("get", "weather");
    }

    @Test
    void exactNameMatchScoresHighestOfAllDimensions() {
        ToolIndexEntry entry = ToolIndexEntry.buildIndex(
                Map.of("getWeather", tool("getWeather", "天气预报查询"))).get(0);

        int exactScore = entry.score("getWeather", ToolIndexEntry.tokenize("getWeather"));
        int substringScore = entry.score("weat", ToolIndexEntry.tokenize("weat"));

        assertThat(exactScore).isGreaterThan(substringScore);
    }

    @Test
    void toolsWithNoOverlapAtAllScoreZero() {
        ToolIndexEntry entry = ToolIndexEntry.buildIndex(
                Map.of("getWeather", tool("getWeather", "天气预报查询"))).get(0);

        int score = entry.score("sendSlackMessage", ToolIndexEntry.tokenize("sendSlackMessage"));

        assertThat(score).isZero();
    }

    @Test
    void matchesOnDescriptionTokensNotJustName() {
        ToolIndexEntry entry = ToolIndexEntry.buildIndex(
                Map.of("queryDb", tool("queryDb", "execute a database report"))).get(0);

        int score = entry.score("report", ToolIndexEntry.tokenize("report"));

        assertThat(score).isPositive();
    }
}
