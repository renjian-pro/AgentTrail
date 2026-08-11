package com.agenttrail.runtime.agent;

import com.agenttrail.platform.identity.Principal;
import com.agenttrail.runtime.RuntimeProfile;
import com.agenttrail.runtime.api.support.FakeAgentRuntimePort;
import com.agenttrail.runtime.tool.ToolDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentRoutingTest {

    @Test
    void ruleRoutingWinsWithoutInvokingModelFallback() {
        AgentDefinition research = definition("research", Set.of("research", "sources"));
        AgentDefinition writing = definition("writing", Set.of("draft"));
        AtomicBoolean fallbackCalled = new AtomicBoolean();

        AgentRouter router = new AgentRouter(new AgentRegistry(List.of(research, writing)), (message, candidates) -> {
            fallbackCalled.set(true);
            return Optional.of("writing");
        });

        assertThat(router.route("please research sources")).contains(new AgentRouter.Route("research", AgentRouter.Route.Source.RULE));
        assertThat(fallbackCalled).isFalse();
    }

    @Test
    void fallbackOnlyRunsWhenNoRuleMatches() {
        AgentRouter router = new AgentRouter(new AgentRegistry(List.of(definition("writing", Set.of("draft")))),
                (message, candidates) -> Optional.of("writing"));

        assertThat(router.route("make it concise")).contains(new AgentRouter.Route("writing", AgentRouter.Route.Source.MODEL));
    }

    @Test
    void childAgentReceivesScopedToolsAndCappedBudget() {
        FakeAgentRuntimePort runtime = new FakeAgentRuntimePort().scriptCallResult("unused", "ok");
        AgentDefinition agent = new AgentDefinition("research", "", RuntimeProfile.defaults(),
                List.of(new ToolDefinition("web", "", Map.of(), ToolDefinition.RiskLevel.READ_ONLY)),
                new AgentDefinition.InputContract(Set.of("research")),
                new AgentDefinition.OutputContract("text/plain"),
                new AgentDefinition.AgentPolicy(100, Duration.ofSeconds(30), 2, Set.of("web")));

        new SubAgentRunner(new AgentRegistry(List.of(agent)), runtime)
                .run("research", "research this", new Principal("u-1"), 0, 200,
                        Map.of("web", true, "admin", true));

        assertThat(runtime.receivedRequests()).hasSize(1);
        assertThat(runtime.receivedRequests().getFirst().toolParams()).containsOnlyKeys("web");
        assertThat(runtime.receivedRequests().getFirst().budget().maxTokens()).isEqualTo(100L);
        assertThatThrownBy(() -> new SubAgentRunner(new AgentRegistry(List.of(agent)), runtime)
                .run("research", "research this", new Principal("u-1"), 2, 200, Map.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void duplicateAgentIdsFailFast() {
        AgentDefinition one = definition("same", Set.of());
        assertThatThrownBy(() -> new AgentRegistry(List.of(one, one)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate agent id");
    }

    private static AgentDefinition definition(String id, Set<String> keywords) {
        return new AgentDefinition(id, id, RuntimeProfile.defaults(), List.of(),
                new AgentDefinition.InputContract(keywords), null, null);
    }
}
