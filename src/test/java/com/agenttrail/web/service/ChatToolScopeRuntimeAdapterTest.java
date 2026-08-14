package com.agenttrail.web.service;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.identity.Principal;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.runtime.api.AgentRequest;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖 ChatApplicationService 之前那个 bug 的根因之一：chat-default 的工具集曾经在 Spring 装配阶段
 * 被 {@code executorFactory.forModel(modelId)} 一次性钉死，联网搜索开关和图表工具都没法按请求生效
 * （用户实测只剩 load_file_content 一个工具）。这里验证修复后每次 start() 都会按当次请求的
 * webSearchEnabled 现查 forModelWithCharts，图表工具始终无条件带上。
 */
class ChatToolScopeRuntimeAdapterTest {

    @Test
    void startResolvesTheExecutorWithWebSearchEnabledWhenTheRequestAsksForIt() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forModelWithCharts(eq("qwen-plus"), eq(true))).thenReturn(executor);
        when(executor.stream(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter =
                new ChatToolScopeRuntimeAdapter(factory, "qwen-plus", new AgentTaskManager());

        AgentRequest request = new AgentRequest(ConversationId.of("conversation-1"), new Principal("user-1"),
                "帮我搜一下今天的新闻", Map.of("webSearchEnabled", true), null, AgentRequest.Budget.UNBOUNDED);

        assertThat(adapter.start(request)).isNotNull();
        verify(factory).forModelWithCharts("qwen-plus", true);
        verify(factory, never()).forModelWithCharts(eq("qwen-plus"), eq(false));
    }

    @Test
    void startResolvesTheExecutorWithoutWebSearchWhenTheRequestDoesNotAskForIt() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forModelWithCharts(eq("qwen-plus"), eq(false))).thenReturn(executor);
        when(executor.stream(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter =
                new ChatToolScopeRuntimeAdapter(factory, "qwen-plus", new AgentTaskManager());

        AgentRequest request = new AgentRequest(ConversationId.of("conversation-1"), new Principal("user-1"),
                "今天是几号", Map.of("webSearchEnabled", false), null, AgentRequest.Budget.UNBOUNDED);

        adapter.start(request);
        verify(factory).forModelWithCharts("qwen-plus", false);
    }

    /**
     * 数据分析业务的生产入口：前端 mode:'analytics' 最终落到这个 toolParams 键上（见
     * ChatApplicationService#request）。选中它之后必须完全绕开 forModelWithCharts——
     * DataAgent 明确不复用文件/Shell/联网搜索等通用工具，只挂分析白名单 + 图表。
     */
    @Test
    void startResolvesTheAnalyticsExecutorWhenTheRequestAsksForItAndIgnoresWebSearch() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forAnalytics(eq("qwen-plus"))).thenReturn(executor);
        when(executor.stream(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter =
                new ChatToolScopeRuntimeAdapter(factory, "qwen-plus", new AgentTaskManager());

        AgentRequest request = new AgentRequest(ConversationId.of("conversation-1"), new Principal("user-1"),
                "上个月的订单量是多少", Map.of("webSearchEnabled", true, "analyticsEnabled", true), null,
                AgentRequest.Budget.UNBOUNDED);

        assertThat(adapter.start(request)).isNotNull();
        verify(factory).forAnalytics("qwen-plus");
        verify(factory, never()).forModelWithCharts(anyString(), anyBoolean());
    }

    @Test
    void cancelStopsTheSharedTaskManagerWithoutTouchingTheExecutorFactory() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentTaskManager taskManager = mock(AgentTaskManager.class);
        ChatToolScopeRuntimeAdapter adapter =
                new ChatToolScopeRuntimeAdapter(factory, "qwen-plus", taskManager);

        adapter.cancel(com.agenttrail.platform.ids.RunId.of("conversation-1"),
                com.agenttrail.runtime.api.CancellationReason.USER_REQUESTED);

        verify(taskManager).stopTask("conversation-1");
        verify(factory, never()).forModelWithCharts(anyString(), anyBoolean());
    }
}
