package com.agenttrail.loop.hook;

@FunctionalInterface
public interface SessionStartHook {
    void onSessionStart(HookContext context);
}
