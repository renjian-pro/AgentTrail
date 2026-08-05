package com.agenttrail.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentObservabilityConfigTest {

    @Test
    void exposesTheParentBasedRatioSamplerAndDocumentsTailSamplingGap() {
        assertThat(new AgentObservabilityConfig().agentTraceSampler().getDescription())
                .contains("parentBased", "ratio=0.1", "error-100%-todo");
    }
}
