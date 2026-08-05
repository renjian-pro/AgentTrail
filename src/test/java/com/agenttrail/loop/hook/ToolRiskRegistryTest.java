package com.agenttrail.loop.hook;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolRiskRegistryTest {

    @Test
    void defaultsClassifyKnownToolsAndFailOpenUnknownNames() {
        ToolRiskRegistry registry = ToolRiskRegistry.defaults();

        assertThat(registry.riskOf("write_file")).isEqualTo(ToolRiskLevel.HIGH_RISK);
        assertThat(registry.riskOf("edit_file")).isEqualTo(ToolRiskLevel.HIGH_RISK);
        assertThat(registry.riskOf("bash")).isEqualTo(ToolRiskLevel.HIGH_RISK);
        assertThat(registry.riskOf("execute_sql")).isEqualTo(ToolRiskLevel.READ_ONLY);
        assertThat(registry.riskOf("list_tables")).isEqualTo(ToolRiskLevel.READ_ONLY);
        assertThat(registry.riskOf("unknown-tool")).isEqualTo(ToolRiskLevel.READ_ONLY);
        assertThat(registry.toolsWithLevel(ToolRiskLevel.HIGH_RISK))
                .containsExactlyInAnyOrder("write_file", "edit_file", "bash");
    }
}
