package com.agenttrail.loop.tools.mcp;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class McpToolSessionTest {

    private static ToolCallback failsWith(String toolName, RuntimeException failure) {
        return new RecordingToolCallback(toolName, "会失败的工具", arguments -> {
            throw failure;
        });
    }

    private static RuntimeException sessionTerminated() {
        return new RuntimeException("MCP session with server terminated");
    }

    @Test
    void rebuildsTheSessionAndRetriesOnceWhenTheServerForgotIt() {
        AtomicInteger connects = new AtomicInteger();
        McpToolSession session = new McpToolSession("test", () -> List.of(
                connects.incrementAndGet() == 1
                        ? failsWith("generate_bar_chart", sessionTerminated())
                        : new RecordingToolCallback("generate_bar_chart", "画图", "https://example.test/chart.png")),
                2);

        List<ToolCallback> tools = session.toolCallbacks();

        assertThat(tools.get(0).call("{}")).isEqualTo("https://example.test/chart.png");
        assertThat(connects).hasValue(2);
    }

    @Test
    void reusesTheRebuiltSessionForLaterCallsInsteadOfReconnectingEveryTime() {
        AtomicInteger connects = new AtomicInteger();
        McpToolSession session = new McpToolSession("test", () -> List.of(
                connects.incrementAndGet() == 1
                        ? failsWith("generate_bar_chart", sessionTerminated())
                        : new RecordingToolCallback("generate_bar_chart", "画图", "https://example.test/chart.png")),
                2);
        ToolCallback tool = session.toolCallbacks().get(0);

        tool.call("{}");
        tool.call("{}");

        assertThat(connects).hasValue(2);
    }

    /** 会话没死的失败（工具自己报错、参数非法）原样抛出，不能被当成"重连就好了"吞掉。 */
    @Test
    void propagatesOrdinaryToolFailuresWithoutRebuildingTheSession() {
        AtomicInteger connects = new AtomicInteger();
        McpToolSession session = new McpToolSession("test", () -> {
            connects.incrementAndGet();
            return List.of(failsWith("generate_bar_chart", new IllegalStateException("参数不合法")));
        }, 2);
        ToolCallback tool = session.toolCallbacks().get(0);

        assertThatThrownBy(() -> tool.call("{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("参数不合法");
        assertThat(connects).hasValue(1);
    }

    /** 重连之后还是同样的错，就认输——否则服务真挂了会变成无限重连。 */
    @Test
    void retriesAtMostOnceWhenTheRebuiltSessionIsAlsoDead() {
        AtomicInteger connects = new AtomicInteger();
        McpToolSession session = new McpToolSession("test", () -> {
            connects.incrementAndGet();
            return List.of(failsWith("generate_bar_chart", sessionTerminated()));
        }, 2);
        ToolCallback tool = session.toolCallbacks().get(0);

        assertThatThrownBy(() -> tool.call("{}")).hasMessageContaining("MCP session with server terminated");
        assertThat(connects).hasValue(2);
    }

    /** 首次建连就失败时返回空列表（调用方据此不挂载这个工具），并且不缓存失败结果。 */
    @Test
    void returnsAnEmptyListWhenTheServerIsUnreachableAndDoesNotCacheThatFailure() {
        AtomicInteger connects = new AtomicInteger();
        McpToolSession session = new McpToolSession("test", () -> {
            connects.incrementAndGet();
            throw new IllegalStateException("connection refused");
        }, 2);

        assertThat(session.toolCallbacks()).isEmpty();
        assertThat(connects).hasValue(2);

        assertThat(session.toolCallbacks()).isEmpty();
        assertThat(connects).hasValue(4);
    }

    /** 代理要保留工具定义，否则重建会话之后模型看到的工具名/描述就变了。 */
    @Test
    void keepsTheToolDefinitionVisibleThroughTheProxy() {
        McpToolSession session = new McpToolSession("test",
                () -> List.of(new RecordingToolCallback("generate_pie_chart", "画饼图", "ok")), 1);

        ToolCallback tool = session.toolCallbacks().get(0);

        assertThat(tool.getToolDefinition().name()).isEqualTo("generate_pie_chart");
        assertThat(tool.getToolDefinition().description()).isEqualTo("画饼图");
    }
}
