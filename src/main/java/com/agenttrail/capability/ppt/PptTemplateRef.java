package com.agenttrail.capability.ppt;

/** 任务选定的不可漂移模板引用；path 只是执行位置，id/version/checksum 才是业务身份。 */
public record PptTemplateRef(String templateId, String version, String artifactId, String checksum,
        String templatePath) {
    public PptTemplateRef {
        if (templateId == null || templateId.isBlank() || version == null || version.isBlank()
                || artifactId == null || artifactId.isBlank() || checksum == null || checksum.isBlank()
                || templatePath == null || templatePath.isBlank()) {
            throw new IllegalArgumentException("PPT template reference is incomplete");
        }
    }

    public static PptTemplateRef from(PptTemplateVersion template) {
        return new PptTemplateRef(template.templateId(), template.version(), template.artifactId(),
                template.checksum(), template.templatePath());
    }
}
