package com.agenttrail.capability.ppt;

/** 模板某个 shape 的字段契约；required/limit 在上线校验和渲染硬兜底中都会使用。 */
public record PptTemplateField(String shapeName, PptFieldType type, boolean required, int maxChars) {
    public PptTemplateField {
        if (shapeName == null || shapeName.isBlank() || type == null) {
            throw new IllegalArgumentException("PPT template shape and field type are required");
        }
        if (maxChars < 0) {
            throw new IllegalArgumentException("PPT template maxChars must not be negative");
        }
    }
}
