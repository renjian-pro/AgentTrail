package com.agenttrail.capability.ppt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * 模板上线前的确定性校验。Java 侧先检查文件/OOXML/checksum/字段契约；Python 渲染冒烟测试
 * 仍由发布验收负责，因为 shape 的递归复制属于 python-pptx 能力，不应让状态机猜测映射。
 */
public final class PptTemplateValidator {
    private PptTemplateValidator() {
    }

    public static void validate(PptTemplateVersion template) {
        Path path = Path.of(template.templatePath()).toAbsolutePath().normalize();
        if (template.status() == PptTemplateStatus.DISABLED) {
            throw new PptGenerationException("PPT 模板版本已禁用: " + template.templateId() + "/" + template.version());
        }
        if (!Files.isRegularFile(path)) {
            throw new PptGenerationException("PPT 模板文件不存在: " + path);
        }
        if (!template.checksum().equalsIgnoreCase(PptTemplateChecksum.sha256(path))) {
            throw new PptGenerationException("PPT 模板 checksum 不匹配: " + template.templateId()
                    + "/" + template.version());
        }
        try (ZipFile zip = new ZipFile(path.toFile())) {
            if (zip.getEntry("ppt/presentation.xml") == null || zip.getEntry("[Content_Types].xml") == null) {
                throw new PptGenerationException("PPT 模板不是有效的 OOXML 文件: " + path);
            }
        } catch (java.io.IOException failure) {
            throw new PptGenerationException("读取 PPT 模板失败: " + path, failure);
        }
        Set<String> seenShapes = new HashSet<>();
        for (PptTemplateField field : template.templateSchema().values()) {
            if (!seenShapes.add(field.shapeName())) {
                throw new PptGenerationException("PPT 模板 shapeName 重复: " + field.shapeName());
            }
            if (field.maxChars() == 0 && field.type() == PptFieldType.TEXT && field.required()) {
                throw new PptGenerationException("PPT 模板必填文本字段没有容量: " + field.shapeName());
            }
        }
        if (template.supportedPageTypes().isEmpty()) {
            throw new PptGenerationException("PPT 模板没有声明支持的页面类型: " + template.templateId());
        }
    }
}
