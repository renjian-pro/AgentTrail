package com.agenttrail.loop.hook;

@FunctionalInterface
public interface PostToolUseHook {
    void afterToolUse(HookContext context, ToolInvocation invocation, String result, boolean success);
}
