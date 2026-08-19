package com.agenttrail.capability.ppt;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 可复现的模板版本注册对象。任务保存 templateId/version 后永远解析回同一 artifact/checksum，
 * 不会因为模板目录里的“最新版”变化而让断点恢复漂移。
 */
public record PptTemplateVersion(String templateId, String version, String name, String description,
        Set<String> styleTags, Set<PptPageType> supportedPageTypes,
        Map<String, PptTemplateField> templateSchema, String artifactId, String checksum,
        PptTemplateStatus status, long createdAtMillis, String templatePath) {
    public PptTemplateVersion {
        if (templateId == null || templateId.isBlank() || version == null || version.isBlank()
                || artifactId == null || artifactId.isBlank() || checksum == null || checksum.isBlank()
                || status == null || templatePath == null || templatePath.isBlank()) {
            throw new IllegalArgumentException("PPT template identity, artifact and checksum are required");
        }
        styleTags = styleTags == null ? Set.of() : Set.copyOf(styleTags);
        supportedPageTypes = supportedPageTypes == null ? Set.of() : Set.copyOf(supportedPageTypes);
        templateSchema = templateSchema == null ? Map.of() : Map.copyOf(templateSchema);
    }

    /** 当前默认模板的最小契约，供开发环境注册和兼容旧 Schema 使用。 */
    public static PptTemplateVersion defaultContract(String path, String checksum) {
        return new PptTemplateVersion("default", "1", "AgentTrail Default", "内置默认模板",
                Set.of("clean", "business"), Set.of(PptPageType.COVER, PptPageType.CONTENT),
                Map.of(
                        PptTemplateSpec.TITLE_SHAPE,
                        new PptTemplateField(PptTemplateSpec.TITLE_SHAPE, PptFieldType.TEXT, true,
                                PptTemplateSpec.TITLE_FONT_LIMIT),
                        PptTemplateSpec.SUBTITLE_SHAPE,
                        new PptTemplateField(PptTemplateSpec.SUBTITLE_SHAPE, PptFieldType.TEXT, false,
                                PptTemplateSpec.SUBTITLE_FONT_LIMIT),
                        PptTemplateSpec.CONTENT_TITLE_SHAPE,
                        new PptTemplateField(PptTemplateSpec.CONTENT_TITLE_SHAPE, PptFieldType.TEXT, true,
                                PptTemplateSpec.CONTENT_TITLE_FONT_LIMIT),
                        PptTemplateSpec.CONTENT_BODY_SHAPE,
                        new PptTemplateField(PptTemplateSpec.CONTENT_BODY_SHAPE, PptFieldType.TEXT, true,
                                PptTemplateSpec.CONTENT_BODY_FONT_LIMIT)),
                "ppt-template/default/1", checksum, PptTemplateStatus.ACTIVE,
                System.currentTimeMillis(), path);
    }

    /** 内置富视觉模板契约；description 同时给 Schema 模型提供逐页字段映射。 */
    public static PptTemplateVersion richContract(String path, String checksum) {
        return new PptTemplateVersion("default", "1", "AgentTrail Rich",
                "COVER: title(7), description(30), author(10); "
                        + "CATALOG: catalog1(9), catalog2(9), catalog3(9); "
                        + "COMPARE: title(9), content1(60), content2(60); "
                        + "CONTENT: title(9), subTitle(4), content(55), image; END: title(5)",
                Set.of("technology", "dark", "visual"),
                Set.of(PptPageType.COVER, PptPageType.CATALOG, PptPageType.COMPARE,
                        PptPageType.CONTENT, PptPageType.END),
                Map.ofEntries(
                        Map.entry("title", new PptTemplateField("title", PptFieldType.TEXT, false, 9)),
                        Map.entry("description",
                                new PptTemplateField("description", PptFieldType.TEXT, false, 30)),
                        Map.entry("author", new PptTemplateField("author", PptFieldType.TEXT, false, 10)),
                        Map.entry("catalog1", new PptTemplateField("catalog1", PptFieldType.TEXT, false, 9)),
                        Map.entry("catalog2", new PptTemplateField("catalog2", PptFieldType.TEXT, false, 9)),
                        Map.entry("catalog3", new PptTemplateField("catalog3", PptFieldType.TEXT, false, 9)),
                        Map.entry("content1", new PptTemplateField("content1", PptFieldType.TEXT, false, 60)),
                        Map.entry("content2", new PptTemplateField("content2", PptFieldType.TEXT, false, 60)),
                        Map.entry("subTitle", new PptTemplateField("subTitle", PptFieldType.TEXT, false, 4)),
                        Map.entry("content", new PptTemplateField("content", PptFieldType.TEXT, false, 55)),
                        Map.entry("image", new PptTemplateField("image", PptFieldType.IMAGE, false, 0))),
                "ppt-template/default/1", checksum, PptTemplateStatus.ACTIVE,
                System.currentTimeMillis(), path);
    }
}
