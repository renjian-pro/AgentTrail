package com.agenttrail.loop.hook;

import java.util.Objects;

/**
 * Production pre-tool policy boundary. It validates the immutable invocation before dispatch and
 * makes the configured risk registry available to the policy layer without coupling hooks to the loop.
 */
public final class ToolPolicyPreToolUseHook implements PreToolUseHook {

    private final ToolRiskRegistry riskRegistry;

    public ToolPolicyPreToolUseHook(ToolRiskRegistry riskRegistry) {
        this.riskRegistry = Objects.requireNonNull(riskRegistry, "riskRegistry");
    }

    @Override
    public void beforeToolUse(HookContext context, ToolInvocation invocation) {
        if (invocation == null || invocation.toolName() == null || invocation.toolName().isBlank()) {
            throw new IllegalArgumentException("tool name is required before dispatch");
        }
        // Resolve the risk here so policy extensions can reject/approve by level without changing the loop.
        riskRegistry.riskOf(invocation.toolName());
    }
}
