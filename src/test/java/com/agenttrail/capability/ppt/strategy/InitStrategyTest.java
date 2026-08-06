package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InitStrategyTest {

    private final InitStrategy strategy = new InitStrategy();

    @Test
    void passesThroughAValidRequirement() {
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT");

        assertThat(strategy.execute(context)).isSameAs(context);
    }

    @Test
    void rejectsBlankRequirement() {
        assertThatThrownBy(() -> strategy.execute(PptGenerationContext.initial("conv-1", "   ")))
                .isInstanceOf(PptGenerationException.class);
    }
}
