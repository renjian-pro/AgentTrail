package com.agenttrail.loop.core;

import com.agenttrail.loop.core.support.RecordingToolCallback;
import com.agenttrail.loop.core.support.ScriptedChatModel;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.loop.model.ThinkingMode;
import com.agenttrail.loop.tools.search.ToolCatalog;
import com.agenttrail.loop.tools.search.ToolSearchConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;

import static com.agenttrail.loop.core.support.ChatResponses.text;
import static com.agenttrail.loop.core.support.ChatResponses.toolCall;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ToolSearch（issue #6）接入 loop 之后的端到端行为：发现工具和能调用工具之间必须隔一轮，
 * 且是靠"每轮都重新组装工具列表"这个既有机制自然做到的，不需要 loop 单独为它加特殊分支。
 */
class AgentLoopExecutorToolSearchTest {

    private static AgentLoopExecutor executorWith(ScriptedChatModel chatModel, ToolCatalog catalog) {
        return AgentLoopExecutor.builder(chatModel, List.of(), 5)
                .toolCatalog(catalog)
                .build();
    }

    @Test
    void discoveredToolIsInvisibleInTheDiscoveringRoundButAvailableInTheNext() {
        RecordingToolCallback getWeather = new RecordingToolCallback("getWeather", "查询天气预报", "24度，晴");
        ToolCatalog catalog = ToolCatalog.of(ToolSearchConfig.defaults(), List.of(getWeather), null);

        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "search_tools", "{\"query\":\"天气\"}")),
                List.of(toolCall("call-2", "getWeather", "{\"city\":\"上海\"}")),
                List.of(text("上海今天24度，晴"))
        );

        List<AgentStreamEvent> events = executorWith(chatModel, catalog)
                .stream("上海天气怎么样", new RunnableParams("conv-1", "user-1"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // 第一轮（发现前）挂给模型的工具清单里不能有 getWeather——它这时候还没被搜到
        assertThat(chatModel.toolNamesAtRound(0)).doesNotContain("getWeather").contains("search_tools");
        // 第二轮：上一轮搜到了，这一轮的工具清单里必须出现
        assertThat(chatModel.toolNamesAtRound(1)).contains("getWeather");

        assertThat(getWeather.recordedArguments()).containsExactly("{\"city\":\"上海\"}");
        assertThat(events).contains(new AgentStreamEvent.ToolEnd("getWeather", "call-2", "24度，晴"));
    }

    /** 同一个 catalog（同一份索引）跨请求复用，但两次对话各自发现的工具互不可见。 */
    @Test
    void concurrentConversationsDoNotLeakEachOthersDiscoveredTools() {
        RecordingToolCallback getWeather = new RecordingToolCallback("getWeather", "查询天气预报", "ok");
        ToolCatalog catalog = ToolCatalog.of(ToolSearchConfig.defaults(), List.of(getWeather), null);

        ScriptedChatModel chatModelA = new ScriptedChatModel(
                List.of(toolCall("call-1", "search_tools", "{\"query\":\"天气\"}")),
                List.of(text("done"))
        );
        executorWith(chatModelA, catalog)
                .stream("查天气", new RunnableParams("conv-a", "user-a"))
                .collectList()
                .block(Duration.ofSeconds(5));

        ScriptedChatModel chatModelB = new ScriptedChatModel(List.of(text("hi")));
        executorWith(chatModelB, catalog)
                .stream("随便聊聊", new RunnableParams("conv-b", "user-b"))
                .collectList()
                .block(Duration.ofSeconds(5));

        // B 会话从没搜过，第一轮就不该看到 A 会话发现的工具
        assertThat(chatModelB.toolNamesAtRound(0)).doesNotContain("getWeather");
    }

    /** 没配 catalog 时，ToolSearch 机制完全不介入——行为和原来一模一样。 */
    @Test
    void behavesExactlyAsBeforeWhenNoCatalogIsConfigured() {
        RecordingToolCallback echo = new RecordingToolCallback("echo", "echoes", "pong");
        ScriptedChatModel chatModel = new ScriptedChatModel(
                List.of(toolCall("call-1", "echo", "{}")),
                List.of(text("done"))
        );
        AgentLoopExecutor executor = new AgentLoopExecutor(chatModel, List.of((ToolCallback) echo), 5);

        executor.stream("echo", new RunnableParams("conv-1", "user-1")).collectList().block(Duration.ofSeconds(5));

        assertThat(chatModel.toolNamesAtRound(0)).containsExactly("echo");
    }
}
