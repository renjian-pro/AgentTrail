package com.agenttrail.runtime.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentRegistry} 目前还没有生产使用方——它和 {@link AgentDefinition} 是 Phase 3 的落点：
 * 把 {@code AgentLoopExecutorFactory} 那 5 个 {@code forXxx} 分支换成可声明的能力定义。
 * 在那之前保留这组断言，是为了让"重复 id 必须启动即失败"这条契约不至于在重写时被悄悄丢掉。
 *
 * <p>原 {@code AgentRoutingTest} 里针对 {@code AgentRouter}/{@code SubAgentRunner} 的三个用例已随
 * 那两个类在 Phase -1 删除：它们的路由规则只是 {@code keywords.contains()}，重做意图识别时不会沿用。
 */
class AgentRegistryTest {

    @Test
    void duplicateAgentIdsFailFast() {
        AgentDefinition one = definition("same");
        assertThatThrownBy(() -> new AgentRegistry(List.of(one, one)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate agent id");
    }

    @Test
    void lookupByIdReturnsTheRegisteredDefinition() {
        AgentDefinition research = definition("research");
        AgentRegistry registry = new AgentRegistry(List.of(research, definition("writing")));

        assertThat(registry.find("research")).contains(research);
        assertThat(registry.find("absent")).isEmpty();
        assertThat(registry.all()).hasSize(2);
    }

    private static AgentDefinition definition(String id) {
        return new AgentDefinition(id, id, "default", List.of(),
                new AgentDefinition.InputContract(Set.of()), null, null);
    }
}
