package com.agenttrail.web.service;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.InMemoryPauseStateStore;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.pause.PauseReason;
import com.agenttrail.loop.pause.PauseState;
import com.agenttrail.loop.pause.PauseStateStore;
import com.agenttrail.platform.tools.ResumeSafePoint;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.identity.Principal;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.ResumeCommand;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 暂停快照，只填 resume 变体判定真正会读到的字段。 */
    private static PauseConfig pauseConfigHolding(String conversationId, Map<String, Object> toolParams) {
        PauseStateStore store = new InMemoryPauseStateStore();
        store.save(new PauseState(conversationId, List.of(), List.of(), PauseReason.HITL_APPROVAL,
                ResumeSafePoint.BEFORE_TOOL_EXECUTION, "上个月的订单量是多少",
                new RunnableParams(conversationId, "user-1", toolParams), 1, 0L));
        return new PauseConfig(Set.of(), store);
    }

    /**
     * issue #96。`AgentRuntimePort.resume` 只给 RunId/ResumeCommand，拿不到当次请求，所以这里
     * 曾经固定走 forModelWithCharts(modelId, false)——被中断的分析会话恢复后拿到的是普通聊天
     * 执行器，工具集整个换掉。当时的辩护是"分析执行器不接受 HITL 中断所以撞不到"，但那依赖
     * "分析工具永远不进审批名单"这个前提，ToolRiskRegistry 改一次就失效。
     */
    @Test
    void resumeRebuildsTheSameExecutorVariantTheRunWasPausedOn() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forAnalytics(eq("qwen-plus"))).thenReturn(executor);
        when(executor.resume(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter = new ChatToolScopeRuntimeAdapter(factory, "qwen-plus",
                new AgentTaskManager(), pauseConfigHolding("conversation-1", Map.of("analyticsEnabled", true)));

        adapter.resume(com.agenttrail.platform.ids.RunId.of("conversation-1"), new ResumeCommand.Approve());

        verify(factory).forAnalytics("qwen-plus");
        verify(factory, never()).forModelWithCharts(anyString(), anyBoolean());
    }

    /** 普通会话的联网搜索开关同样要还原，否则恢复后模型手里的工具比中断前少。 */
    @Test
    void resumeCarriesTheWebSearchFlagBackFromTheSnapshot() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forModelWithCharts(eq("qwen-plus"), eq(true))).thenReturn(executor);
        when(executor.resume(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter = new ChatToolScopeRuntimeAdapter(factory, "qwen-plus",
                new AgentTaskManager(), pauseConfigHolding("conversation-1", Map.of("webSearchEnabled", true)));

        adapter.resume(com.agenttrail.platform.ids.RunId.of("conversation-1"), new ResumeCommand.Approve());

        verify(factory).forModelWithCharts("qwen-plus", true);
    }

    /**
     * 快照走 JSON 往返，历史数据里布尔值以字符串形式出现过。只认 Boolean.TRUE 会把恢复出来的
     * 分析会话静默判成普通聊天——静默降级正是这张票要消灭的那类故障。
     */
    @Test
    void resumeAcceptsFlagsThatSurvivedJsonRoundTripAsStrings() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forAnalytics(eq("qwen-plus"))).thenReturn(executor);
        when(executor.resume(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter = new ChatToolScopeRuntimeAdapter(factory, "qwen-plus",
                new AgentTaskManager(), pauseConfigHolding("conversation-1", Map.of("analyticsEnabled", "true")));

        adapter.resume(com.agenttrail.platform.ids.RunId.of("conversation-1"), new ResumeCommand.Approve());

        verify(factory).forAnalytics("qwen-plus");
    }

    /** 没配暂停机制时退回既有行为，由下游 AgentLoopExecutor#resume 抛明确异常，不在这层提前失败。 */
    @Test
    void resumeFallsBackToPlainChatWhenNoSnapshotIsAvailable() {
        AgentLoopExecutorFactory factory = mock(AgentLoopExecutorFactory.class);
        AgentLoopExecutor executor = mock(AgentLoopExecutor.class);
        when(factory.forModelWithCharts(eq("qwen-plus"), eq(false))).thenReturn(executor);
        when(executor.resume(any(), any())).thenReturn(Flux.empty());

        ChatToolScopeRuntimeAdapter adapter =
                new ChatToolScopeRuntimeAdapter(factory, "qwen-plus", new AgentTaskManager());

        adapter.resume(com.agenttrail.platform.ids.RunId.of("conversation-1"), new ResumeCommand.Approve());

        verify(factory).forModelWithCharts("qwen-plus", false);
    }
}
