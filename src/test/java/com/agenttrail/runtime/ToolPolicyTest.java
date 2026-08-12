package com.agenttrail.runtime;

import com.agenttrail.loop.hook.HookContext;
import com.agenttrail.loop.hook.ToolInvocation;
import com.agenttrail.loop.hook.ToolPolicyPreToolUseHook;
import com.agenttrail.loop.hook.ToolRiskRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolPolicyTest {
    @Test
    void validatesEveryInvocationBeforeDispatch() {
        ToolPolicyPreToolUseHook hook = new ToolPolicyPreToolUseHook(ToolRiskRegistry.defaults());

        assertThatCode(() -> hook.beforeToolUse(new HookContext("c", "u", 1),
                new ToolInvocation("call-1", "read_file", "{}"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> hook.beforeToolUse(new HookContext("c", "u", 1),
                new ToolInvocation("call-2", "", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
