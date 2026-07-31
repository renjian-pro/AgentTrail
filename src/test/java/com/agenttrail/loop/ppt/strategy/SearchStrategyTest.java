package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptRequirement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchStrategyTest {

    @Test
    void producesCannedMaterialsMentioningTheRequirementTopic() {
        PptGenerationContext context = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "Spring AI Agent", "团队内部", 2, "专业简洁"));

        PptGenerationContext result = new SearchStrategy().execute(context);

        assertThat(result.searchMaterials()).isNotEmpty();
        assertThat(result.searchMaterials()).allSatisfy(material -> assertThat(material).contains("Spring AI Agent"));
    }
}
