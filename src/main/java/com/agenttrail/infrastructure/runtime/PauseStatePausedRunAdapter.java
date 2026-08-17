package com.agenttrail.infrastructure.runtime;

import com.agenttrail.capability.chat.application.PausedRunPort;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import com.agenttrail.loop.pause.PauseStateStore;
import com.agenttrail.loop.pause.ToolArgumentSanitizer;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 把 Loop 的完整 PauseState 投影成应用层可安全查询的只读视图。 */
public final class PauseStatePausedRunAdapter implements PausedRunPort {

    private static final String WEB_SEARCH_PARAM = "webSearchEnabled";
    private static final String ANALYTICS_PARAM = "analyticsEnabled";

    private final PauseStateStore store;
    private final ToolRiskRegistry riskRegistry;

    public PauseStatePausedRunAdapter(PauseStateStore store, ToolRiskRegistry riskRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.riskRegistry = Objects.requireNonNull(riskRegistry, "riskRegistry");
    }

    @Override
    public Optional<PausedRun> find(String conversationId) {
        return store.find(conversationId).map(state -> {
            Map<String, Object> toolParams = state.params().toolParams();
            String modelId = "unknown".equals(state.modelId()) ? null : state.modelId();
            return new PausedRun(state.conversationId(), state.params().userId(), modelId,
                    state.reason().name(), state.pausedAtMillis(),
                    flagEnabled(toolParams, WEB_SEARCH_PARAM), flagEnabled(toolParams, ANALYTICS_PARAM),
                    state.pendingToolCalls().stream()
                            .map(tool -> new PendingTool(tool.id(), tool.name(),
                                    ToolArgumentSanitizer.sanitize(tool.arguments()),
                                    riskRegistry.riskOf(tool.name()).name()))
                            .toList());
        });
    }

    private static boolean flagEnabled(Map<String, Object> params, String key) {
        Object value = params.get(key);
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value));
    }
}
