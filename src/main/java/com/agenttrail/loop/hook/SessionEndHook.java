package com.agenttrail.loop.hook;

@FunctionalInterface
public interface SessionEndHook {
    void onSessionEnd(HookContext context, boolean success);
}
