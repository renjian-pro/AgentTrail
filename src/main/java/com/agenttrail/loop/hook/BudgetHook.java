package com.agenttrail.loop.hook;

@FunctionalInterface
public interface BudgetHook {
    void onRoundUsage(HookContext context, long promptTokens, long completionTokens);
}
