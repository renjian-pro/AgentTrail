package com.agenttrail.web.service;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRunSnapshot;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.api.ResumeCommand;
import com.agenttrail.runtime.api.legacy.LegacyAgentLoopExecutorAdapter;

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

    public ChatToolScopeRuntimeAdapter(AgentLoopExecutorFactory executorFactory, String modelId,
                                       AgentTaskManager taskManager) {
        this.executorFactory = Objects.requireNonNull(executorFactory, "executorFactory");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
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
     * 拿不到这一次 HITL 审批请求带的 webSearchEnabled/analyticsEnabled——按普通聊天、不挂联网搜索
     * 处理，图表工具仍然无条件带上。这跟审批入口 {@code ChatApplicationService#approve} 当前的
     * 行为一致（它也没有把 {@code AgentApprovalRequest#webSearchEnabled} 传下来）；分析执行器本身
     * 也不接受 HITL 审批中断，走普通聊天分支不会丢失真实需要恢复的分析对话。
     */
    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        AgentLoopExecutor executor = executorFactory.forModelWithCharts(modelId, false);
        return new LegacyAgentLoopExecutorAdapter(executor, taskManager).resume(runId, command);
    }

    private boolean webSearchEnabled(AgentRequest request) {
        return Boolean.TRUE.equals(request.toolParams().get(WEB_SEARCH_PARAM));
    }

    private boolean analyticsEnabled(AgentRequest request) {
        return Boolean.TRUE.equals(request.toolParams().get(ANALYTICS_PARAM));
    }

    private LegacyAgentLoopExecutorAdapter delegateFor(AgentRequest request) {
        AgentLoopExecutor executor = analyticsEnabled(request)
                ? executorFactory.forAnalytics(modelId)
                : executorFactory.forModelWithCharts(modelId, webSearchEnabled(request));
        return new LegacyAgentLoopExecutorAdapter(executor, taskManager);
    }
}
