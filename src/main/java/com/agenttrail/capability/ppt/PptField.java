package com.agenttrail.capability.ppt;

/** 页面字段的语义值。value 支持图表/表格的结构化 JSON，text/artifactId 覆盖常用快捷路径。 */
public record PptField(PptFieldType type, String text, String artifactId, Object value) {
    public PptField {
        if (type == null) {
            throw new IllegalArgumentException("PPT field type must not be null");
        }
        if (type == PptFieldType.TEXT && text == null && value == null) {
            throw new IllegalArgumentException("PPT text field must have a value");
        }
        if (type == PptFieldType.IMAGE && artifactId == null && value == null) {
            throw new IllegalArgumentException("PPT image field must reference an artifact");
        }
    }

    public static PptField text(String text) {
        return new PptField(PptFieldType.TEXT, text, null, null);
    }

    public static PptField image(String artifactId) {
        return new PptField(PptFieldType.IMAGE, null, artifactId, null);
    }
}
