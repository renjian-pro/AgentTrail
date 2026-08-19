package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.capability.ppt.PptField;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptFieldType;
import com.agenttrail.capability.ppt.PptPage;
import com.agenttrail.capability.ppt.PptPageType;
import com.agenttrail.capability.ppt.PptSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** 动态页面必须完整到达渲染边界，而不是只落回 legacy title/content 字段。 */
class RenderStrategyDynamicSchemaTest {

    @Test
    void preservesPageIdentityTypeTemplateReferenceFieldsAndNotesInRenderPayload(@TempDir Path outputDir)
            throws Exception {
        AtomicReference<String> schemaJson = new AtomicReference<>();
        RenderStrategy strategy = new RenderStrategy((template, schemaFile, outputFile) -> {
            try {
                schemaJson.set(Files.readString(schemaFile));
                Files.writeString(outputFile, "fake-pptx");
            } catch (java.io.IOException failed) {
                throw new IllegalStateException(failed);
            }
        }, outputDir.toString(), null);
        var coverFields = new LinkedHashMap<String, PptField>();
        coverFields.put("title", PptField.text("动态标题"));
        var contentFields = new LinkedHashMap<String, PptField>();
        contentFields.put("slideTitle", PptField.text("内容页"));
        contentFields.put("slideBody", PptField.text("正文"));
        contentFields.put("image", new PptField(PptFieldType.IMAGE, null,
                "http://localhost:9000/ppt/content.png", "科技架构配图"));
        PptSchema schema = new PptSchema("legacy", "legacy", List.of(), null, List.of(
                new PptPage("cover-1", PptPageType.COVER, "COVER", coverFields, "封面备注"),
                new PptPage("page-1", PptPageType.CONTENT, "CONTENT", contentFields, "演讲备注")),
                "default", "1");

        strategy.execute(PptGenerationContext.initial("conv-1", "需求")
                .withTemplatePath("template.pptx").withSchema(schema));

        assertThat(schemaJson).hasValueSatisfying(json -> assertThat(json)
                .contains("\"pageId\":\"cover-1\"")
                .contains("\"pageType\":\"COVER\"")
                .contains("\"templatePageRef\":\"CONTENT\"")
                .contains("\"speakerNotes\":\"演讲备注\"")
                .contains("\"shapeName\":\"slideTitle\"")
                .contains("\"shapeName\":\"image\"")
                .contains("\"type\":\"IMAGE\"")
                .contains("\"url\":\"http://localhost:9000/ppt/content.png\"")
                .contains("动态标题"));
    }
}
