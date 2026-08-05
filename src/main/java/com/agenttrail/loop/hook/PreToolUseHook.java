package com.agenttrail.loop.hook;

@FunctionalInterface
public interface PreToolUseHook {
    void beforeToolUse(HookContext context, ToolInvocation invocation);
}
