package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证 {@link PptContextJson} 能把一份跑到 RENDER 状态、字段全部非空的完整快照原样往返。 */
class PptContextJsonTest {

    @Test
    void writesTheCurrentContextVersionIntoEveryNewSnapshot() {
        String json = PptContextJson.toJson(PptGenerationContext.initial("conv-1", "问题"));

        assertThat(json).contains("\"contextVersion\":1");
        assertThat(PptContextJson.fromJson(json).contextVersion())
                .isEqualTo(PptGenerationContext.CURRENT_CONTEXT_VERSION);
    }

    @Test
    void readsLegacySnapshotWithoutVersionAsTheCurrentCompatibleVersion() {
        String legacyJson = "{\"conversationId\":\"conv-1\",\"userRequirement\":\"问题\"}";

        assertThat(PptContextJson.fromJson(legacyJson).contextVersion())
                .isEqualTo(PptGenerationContext.CURRENT_CONTEXT_VERSION);
    }

    @Test
    void roundTripsAFullyPopulatedContext() {
        PptGenerationContext original = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT")
                .withRequirement(new PptRequirement("标题", "主题", "受众", 3, "专业简洁"))
                .withSearchMaterials(List.of("素材一", "素材二"))
                .withTemplatePath("/tmp/template.pptx")
                .withOutline(new PptOutline("封面标题", "封面副标题", List.of(
                        new PptOutlineSlide("第一页", List.of("要点一", "要点二")))))
                .withSchema(new PptSchema("封面标题", "封面副标题", List.of(
                        new PptContentSlideFill("第一页标题", "第一页正文"))))
                .withOutputPath("/tmp/output.pptx");

        String json = PptContextJson.toJson(original);
        PptGenerationContext restored = PptContextJson.fromJson(json);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    void roundTripsTheInitialContextWhereMostFieldsAreStillNull() {
        PptGenerationContext original = PptGenerationContext.initial("conv-1", "帮我做一份介绍 PPT");

        assertThat(PptContextJson.fromJson(PptContextJson.toJson(original))).isEqualTo(original);
    }

    @Test
    void roundTripsModifyOperationAndBaseArtifactMetadata() {
        PptGenerationContext original = PptGenerationContext.initial("conv-1", "改第二页")
                .withOperationMetadata("MODIFY", 42L, "ppt-artifact-42-old");

        PptGenerationContext restored = PptContextJson.fromJson(PptContextJson.toJson(original));

        assertThat(restored.operation()).isEqualTo("MODIFY");
        assertThat(restored.baseTaskId()).isEqualTo(42L);
        assertThat(restored.baseArtifactId()).isEqualTo("ppt-artifact-42-old");
    }
}
