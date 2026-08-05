package com.agenttrail.loop.hook;

@FunctionalInterface
public interface OnErrorHook {
    void onError(HookContext context, Throwable error);
}
