package com.agenttrail.loop.ppt;

import java.util.List;

/**
 * PPT 生成状态机贯穿全流程的上下文（issue #24）。不可变、"with" 语义——每个
 * {@link PptGenerationStrategy} 拿到当前上下文，做完自己那一段真实副作用之后，返回一个新的、
 * 多了一块数据的上下文，不是原地修改。
 *
 * <p>这也是"按状态粒度做 checkpoint"的具体载体（踩坑点 #44）：某个状态负责产出的字段，
 * 在这个状态真正跑完之前一直是 {@code null}；持久化的是这份上下文本身（见
 * {@link PptTaskStore}），断点恢复时从 DB 反序列化回这个类型，哪个字段还是 {@code null}
 * 就意味着从哪个状态继续——不需要另外维护一份"跑到哪一步了"的元数据，上下文自己就是进度。
 */
public record PptGenerationContext(
        String conversationId,
        String userRequirement,
        PptRequirement requirement,
        List<String> searchMaterials,
        String templatePath,
        PptOutline outline,
        PptSchema schema,
        String outputPath) {

    public static PptGenerationContext initial(String conversationId, String userRequirement) {
        return new PptGenerationContext(conversationId, userRequirement, null, null, null, null, null, null);
    }

    public PptGenerationContext withRequirement(PptRequirement requirement) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }

    public PptGenerationContext withSearchMaterials(List<String> searchMaterials) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }

    public PptGenerationContext withTemplatePath(String templatePath) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }

    public PptGenerationContext withOutline(PptOutline outline) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }

    public PptGenerationContext withSchema(PptSchema schema) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }

    public PptGenerationContext withOutputPath(String outputPath) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath);
    }
}
