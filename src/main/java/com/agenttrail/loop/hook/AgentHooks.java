package com.agenttrail.loop.hook;

import java.util.List;

/** 六个生命周期拦截点的有序 Hook 列表。 */
public record AgentHooks(
        List<SessionStartHook> sessionStart,
        List<PreToolUseHook> preToolUse,
        List<PostToolUseHook> postToolUse,
        List<BudgetHook> budget,
        List<OnErrorHook> onError,
        List<SessionEndHook> sessionEnd) {

    public static final AgentHooks EMPTY =
            new AgentHooks(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public AgentHooks {
        sessionStart = List.copyOf(sessionStart);
        preToolUse = List.copyOf(preToolUse);
        postToolUse = List.copyOf(postToolUse);
        budget = List.copyOf(budget);
        onError = List.copyOf(onError);
        sessionEnd = List.copyOf(sessionEnd);
    }
}
