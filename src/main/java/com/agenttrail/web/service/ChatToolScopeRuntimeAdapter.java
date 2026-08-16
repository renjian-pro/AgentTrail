package com.agenttrail.web.service;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.pause.PauseConfig;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRunSnapshot;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.api.ResumeCommand;
import com.agenttrail.infrastructure.runtime.LegacyAgentLoopExecutorAdapter;

import java.util.Map;
import java.util.Objects;

/**
 * {@code chat-default} 专用的 {@link AgentRuntimePort}：{@link RuntimeProfileRegistry} 里
 * 其它 profile 用的 {@link LegacyAgentLoopExecutorAdapter} 包着一个装配阶段就建好、此后固定不变
 * 的 {@link AgentLoopExecutor}，工具列表没法按请求变化。这里改成每次 {@link #start} 都按本次
 * request 里的 {@code webSearchEnabled} 现查（{@link AgentLoopExecutorFactory} 内部有缓存，
 * 不是真的重新建），图表工具则始终无条件带上——和联网搜索不同，图表生成没有按对话开关的必要
 * （见 {@link AgentLoopExecutorFactory#forModelWithCharts}）。
 *
 * <p>{@code analyticsEnabled} 时整个分支切到 {@link AgentLoopExecutorFactory#forAnalytics}，
 * 忽略 {@code webSearchEnabled}——数据分析能力包明确不复用文件/Shell/联网搜索这些通用工具
 * （见 {@code AnalyticsToolProvider} 类注释），两者是互斥的执行器变体，不是可以叠加的工具开关。
 * 这是数据分析业务第一次接入真实对话入口：此前 {@code forAnalytics} 只被
 * {@code GoldenEvaluationService} 的评测跑批调用过，前端 {@code mode:'analytics'} 字段虽然
 * 一直在发，但 {@code AgentChatRequest#mode()} 从未被读取，选中"数据分析"模式实际上和普通
 * 聊天没有任何区别。
 */
public class ChatToolScopeRuntimeAdapter implements AgentRuntimePort {
    private static final String WEB_SEARCH_PARAM = "webSearchEnabled";
    private static final String ANALYTICS_PARAM = "analyticsEnabled";

    private final AgentLoopExecutorFactory executorFactory;
    private final String modelId;
    private final AgentTaskManager taskManager;
    /** 传 null 表示这套装配不支持暂停恢复，{@link #resume} 退化成按普通聊天恢复。 */
    private final PauseConfig pauseConfig;

    public ChatToolScopeRuntimeAdapter(AgentLoopExecutorFactory executorFactory, String modelId,
                                       AgentTaskManager taskManager) {
        this(executorFactory, modelId, taskManager, null);
    }

    public ChatToolScopeRuntimeAdapter(AgentLoopExecutorFactory executorFactory, String modelId,
                                       AgentTaskManager taskManager, PauseConfig pauseConfig) {
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
        this.pauseConfig = pauseConfig;
    }

    @Override
    public AgentRunHandle start(AgentRequest request) {
        return delegateFor(request).start(request);
    }

    @Override
    public AgentResult call(AgentRequest request) {
        return delegateFor(request).call(request);
    }

    @Override
    public AgentRunSnapshot snapshot(RunId runId) {
        throw new UnsupportedOperationException("ChatToolScopeRuntimeAdapter does not support snapshot: runId=" + runId);
    }

    @Override
    public void cancel(RunId runId, CancellationReason reason) {
        taskManager.stopTask(runId.value());
    }

    /**
     * 恢复时没有当次的 {@link AgentRequest}（接口只给 {@code RunId}/{@code ResumeCommand}），
     * 所以变体从**暂停快照**里取：{@code PauseState} 持久化了当初那次请求的
     * {@code RunnableParams.toolParams}，{@code analyticsEnabled}/{@code webSearchEnabled} 都在里面
     * （见 {@code PauseStateJson#toParamsMap}）。
     *
     * <p>此前这里固定走 {@code forModelWithCharts(modelId, false)}，被中断的分析会话恢复后拿到的是
     * 普通聊天执行器——工具集整个换掉了。当时的辩护是"分析执行器不接受 HITL 审批中断所以撞不到"，
     * 但那依赖"分析工具永远不进审批名单"这个前提，{@code ToolRiskRegistry} 改一次就失效（issue #96）。
     *
     * <p>快照读不到（没配暂停机制、或状态已过期被清理）时退回普通聊天分支——保持既有行为，
     * 让"恢复不了"由下游 {@code AgentLoopExecutor#resume} 抛出明确异常，不在这一层提前失败。
     */
    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        Map<String, Object> pausedParams = pausedToolParams(runId);
        AgentLoopExecutor executor = flagEnabled(pausedParams, ANALYTICS_PARAM)
                ? executorFactory.forAnalytics(modelId)
                : executorFactory.forModelWithCharts(modelId, flagEnabled(pausedParams, WEB_SEARCH_PARAM));
        return new LegacyAgentLoopExecutorAdapter(executor, taskManager).resume(runId, command);
    }

    private Map<String, Object> pausedToolParams(RunId runId) {
        if (pauseConfig == null) {
            return Map.of();
        }
        return pauseConfig.store().find(runId.value())
                .map(state -> state.params().toolParams())
                .orElseGet(Map::of);
    }

    /**
     * 值可能是 {@code Boolean} 也可能是字符串——快照走 JSON 往返，历史数据里两种都出现过，
     * 只认 {@code Boolean.TRUE} 会把恢复出来的分析会话静默判成普通聊天。
     */
    private static boolean flagEnabled(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }

    private boolean webSearchEnabled(AgentRequest request) {
        return flagEnabled(request.toolParams(), WEB_SEARCH_PARAM);
    }

    private boolean analyticsEnabled(AgentRequest request) {
        return flagEnabled(request.toolParams(), ANALYTICS_PARAM);
    }

    private LegacyAgentLoopExecutorAdapter delegateFor(AgentRequest request) {
        AgentLoopExecutor executor = analyticsEnabled(request)
                ? executorFactory.forAnalytics(modelId)
                : executorFactory.forModelWithCharts(modelId, webSearchEnabled(request));
        return new LegacyAgentLoopExecutorAdapter(executor, taskManager);
    }
}
