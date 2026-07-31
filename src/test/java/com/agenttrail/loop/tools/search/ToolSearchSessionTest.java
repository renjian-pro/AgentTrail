package com.agenttrail.loop.tools.search;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSearchSessionTest {

    private static ToolCatalog catalogOf(ToolCallback... tools) {
        return ToolCatalog.of(ToolSearchConfig.defaults(), List.of(tools), null);
    }

    @Test
    void discoversNothingBeforeAnySearch() {
        ToolSearchSession session = catalogOf(
                new RecordingToolCallback("getWeather", "查询天气", "ok")).newSession();

        assertThat(session.discoveredTools()).isEmpty();
    }

    @Test
    void toolBecomesDiscoverableAfterASuccessfulSearch() {
        ToolSearchSession session = catalogOf(
                new RecordingToolCallback("getWeather", "查询天气预报", "ok")).newSession();

        session.toolSearchCallback().call("{\"query\":\"天气\"}");

        assertThat(session.discoveredTools())
                .extracting(tool -> tool.getToolDefinition().name())
                .containsExactly("getWeather");
    }

    /** 两次对话各自独立发现工具，互不泄漏——并发场景下这是硬要求。 */
    @Test
    void discoveredToolsDoNotLeakBetweenSessions() {
        ToolCatalog catalog = catalogOf(new RecordingToolCallback("getWeather", "查询天气预报", "ok"));
        ToolSearchSession sessionA = catalog.newSession();
        ToolSearchSession sessionB = catalog.newSession();

        sessionA.toolSearchCallback().call("{\"query\":\"天气\"}");

        assertThat(sessionA.discoveredTools()).isNotEmpty();
        assertThat(sessionB.discoveredTools()).isEmpty();
    }
}
