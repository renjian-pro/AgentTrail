package com.agenttrail.capability.ppt;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.agenttrail.capability.ppt.strategy.TemplateStrategy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 覆盖模板版本固定、动态 Schema 契约、视觉规划和素材稳定 key 的最小闭环。 */
class PptTemplateContractTest {

    @Test
    void validatesTheCheckedInDefaultTemplateByChecksumAndOoxmlSignature() {
        Path path = Path.of("src/main/resources/ppt-templates/default-template.pptx").toAbsolutePath().normalize();
        InMemoryPptTemplateRegistry registry = new InMemoryPptTemplateRegistry();
        registry.register(PptTemplateVersion.defaultContract(path.toString(), PptTemplateChecksum.sha256(path)));

        assertThat(registry.validate("default", "1").artifactId()).isEqualTo("ppt-template/default/1");

        PptGenerationContext selected = new TemplateStrategy(path.toString(), path.getParent().toString(), registry)
                .execute(PptGenerationContext.initial("conv", "做一份 PPT")
                        .withRequirement(new PptRequirement("标题", "主题", "受众", 1, "专业")));
        assertThat(selected.templateRef().version()).isEqualTo("1");
        assertThat(selected.visualPlan()).isNotNull();
    }

    @Test
    void refusesToMutateAnAlreadyRegisteredVersionWithAnotherChecksum() {
        InMemoryPptTemplateRegistry registry = new InMemoryPptTemplateRegistry();
        PptTemplateVersion first = new PptTemplateVersion("demo", "1", "demo", "demo", Set.of(),
                Set.of(PptPageType.CONTENT), Map.of("content", new PptTemplateField("content", PptFieldType.TEXT,
                        true, 100)), "artifact-1", "checksum-1", PptTemplateStatus.ACTIVE, 1, "missing.pptx");
        registry.register(first);

        PptTemplateVersion changed = new PptTemplateVersion("demo", "1", "demo", "demo", Set.of(),
                Set.of(PptPageType.CONTENT), first.templateSchema(), "artifact-2", "checksum-2",
                PptTemplateStatus.ACTIVE, 2, "missing.pptx");
        assertThatThrownBy(() -> registry.register(changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void exposesTheRichTemplatePageTypesAndActualShapeNames() {
        Path path = Path.of("src/main/resources/ppt-templates/rich-template.pptx").toAbsolutePath().normalize();

        PptTemplateVersion template = PptTemplateVersion.richContract(path.toString(),
                PptTemplateChecksum.sha256(path));

        assertThat(template.supportedPageTypes()).containsExactlyInAnyOrder(
                PptPageType.COVER, PptPageType.CATALOG, PptPageType.COMPARE,
                PptPageType.CONTENT, PptPageType.END);
        assertThat(template.templateSchema()).containsKeys(
                "title", "description", "author", "catalog1", "catalog2", "catalog3",
                "content1", "content2", "subTitle", "content", "image");
        assertThat(template.description()).contains("COVER: title(7)", "CONTENT: title(9)");
    }

    @Test
    void validatesDynamicPagesAndPreservesVisualPlanAndAssetsInCheckpointJson() {
        PptTemplateVersion template = new PptTemplateVersion("demo", "1", "demo", "demo", Set.of(),
                Set.of(PptPageType.CONTENT), Map.of(
                        "title", new PptTemplateField("title", PptFieldType.TEXT, true, 80),
                        "image", new PptTemplateField("image", PptFieldType.IMAGE, false, 0)),
                "artifact-1", "checksum-1", PptTemplateStatus.ACTIVE, 1, "missing.pptx");
        Map<String, PptField> fields = new LinkedHashMap<>();
        fields.put("title", PptField.text("一页内容"));
        PptSchema schema = new PptSchema("标题", "副标题", List.of(), null,
                List.of(new PptPage("page-1", PptPageType.CONTENT, "CONTENT", fields, "讲稿")),
                "demo", "1");
        PptSchemaValidator.validate(schema, template);

        PptVisualPlan visualPlan = PptVisualPlan.defaultFor(new PptRequirement("标题", "主题", "受众", 1, "专业"));
        PptAssetTask asset = PptAssetTask.planned("page-1", "image", PptFieldType.IMAGE,
                "一张图", visualPlan, PptAssetKey.digest("一张图"));
        PptGenerationContext context = PptGenerationContext.initial("conv", "做 PPT")
                .withVisualPlan(visualPlan).withSchema(schema).withAssetTask(asset);

        PptGenerationContext restored = PptContextJson.fromJson(PptContextJson.toJson(context));
        assertThat(restored.visualPlan()).isEqualTo(visualPlan);
        assertThat(restored.assetTasks()).containsExactly(asset);
        assertThat(PptAssetKey.derive("task", "page-1", "image", "一张图"))
                .isEqualTo(PptAssetKey.derive("task", "page-1", "image", "一张图"));
    }

    @Test
    void plansImageFieldsByStablePageAndFieldIdentity() {
        Map<String, PptField> fields = Map.of("hero", PptField.image("artifact-old"));
        PptSchema schema = new PptSchema("标题", "副标题", List.of(), null,
                List.of(new PptPage("page-7", PptPageType.IMAGE_TEXT, "IMAGE_TEXT", fields, null)),
                "demo", "1");

        List<PptAssetTask> planned = PptAssetPlanner.plan(schema, PptVisualPlan.defaultFor(null));

        assertThat(planned).singleElement().satisfies(asset -> {
            assertThat(asset.pageId()).isEqualTo("page-7");
            assertThat(asset.fieldName()).isEqualTo("hero");
            assertThat(asset.status()).isEqualTo(PptAssetStatus.PLANNED);
        });
    }
}
