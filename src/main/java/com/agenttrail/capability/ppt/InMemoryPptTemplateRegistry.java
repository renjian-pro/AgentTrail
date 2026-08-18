package com.agenttrail.capability.ppt;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 轻量模板注册表：开发环境和单元测试使用；模板版本仍以 checksum 固定，不是“当前路径即版本”。
 * 生产环境可将同一接口换成 JDBC/对象存储实现。
 */
public final class InMemoryPptTemplateRegistry implements PptTemplateRegistry {
    private final Map<String, PptTemplateVersion> templates = new ConcurrentHashMap<>();

    @Override
    public void register(PptTemplateVersion template) {
        String key = key(template.templateId(), template.version());
        PptTemplateVersion previous = templates.putIfAbsent(key, template);
        if (previous != null && !previous.checksum().equals(template.checksum())) {
            throw new IllegalStateException("PPT 模板版本 checksum 不可变: " + key);
        }
        if (previous != null && previous.status() != template.status()) {
            templates.put(key, template);
        }
    }

    @Override
    public Optional<PptTemplateVersion> find(String templateId, String version) {
        return Optional.ofNullable(templates.get(key(templateId, version)));
    }

    @Override
    public Optional<PptTemplateVersion> active(String templateId) {
        return templates.values().stream()
                .filter(template -> template.templateId().equals(templateId)
                        && template.status() == PptTemplateStatus.ACTIVE)
                .max(java.util.Comparator.comparing(PptTemplateVersion::version));
    }

    @Override
    public PptTemplateVersion validate(String templateId, String version) {
        PptTemplateVersion template = find(templateId, version)
                .orElseThrow(() -> new PptGenerationException("PPT 模板版本不存在: " + templateId + "/" + version));
        PptTemplateValidator.validate(template);
        return template;
    }

    private static String key(String templateId, String version) {
        if (templateId == null || templateId.isBlank() || version == null || version.isBlank()) {
            throw new IllegalArgumentException("PPT template id and version are required");
        }
        return templateId + "@" + version;
    }
}
