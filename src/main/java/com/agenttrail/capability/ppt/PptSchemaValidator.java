package com.agenttrail.capability.ppt;

import java.util.HashSet;
import java.util.Set;

/**
 * Schema 的结构/业务双重校验。旧默认模板走 legacy title/content 路径；带 pages 的新 Schema
 * 必须拥有稳定 pageId、模板版式引用、非空必填字段，并且不能重复引用其他页面的素材。
 */
public final class PptSchemaValidator {
    private PptSchemaValidator() {
    }

    /** 兼容默认模板调用点的轻量业务校验。带动态 pages 的 Schema 需使用带模板契约的重载。 */
    public static void validate(PptSchema schema) {
        if (schema == null) throw new PptGenerationException("PPT Schema 不能为空");
        if (schema.pages().isEmpty()) {
            validateLegacy(schema);
            return;
        }
        Set<String> pageIds = new HashSet<>();
        for (PptPage page : schema.pages()) {
            if (!pageIds.add(page.pageId())) {
                throw new PptGenerationException("PPT Schema pageId 重复: " + page.pageId());
            }
        }
    }

    public static void validate(PptSchema schema, PptTemplateVersion template) {
        if (schema == null || template == null) {
            throw new PptGenerationException("PPT Schema 和模板契约不能为空");
        }
        if (schema.pages().isEmpty()) {
            validateLegacy(schema);
            return;
        }
        Set<String> pageIds = new HashSet<>();
        for (PptPage page : schema.pages()) {
            if (!pageIds.add(page.pageId())) {
                throw new PptGenerationException("PPT Schema pageId 重复: " + page.pageId());
            }
            if (!template.supportedPageTypes().contains(page.pageType())) {
                throw new PptGenerationException("模板不支持页面类型: " + page.pageType());
            }
            boolean knownPageRef = template.templateSchema().containsKey(page.templatePageRef())
                    || page.pageType().name().equalsIgnoreCase(page.templatePageRef());
            if (!knownPageRef) {
                throw new PptGenerationException("页面引用了未注册的模板版式: " + page.templatePageRef());
            }
            for (var entry : page.fields().entrySet()) {
                PptTemplateField field = template.templateSchema().get(entry.getKey());
                if (field == null) {
                    throw new PptGenerationException("Schema 字段不属于模板版本: " + entry.getKey());
                }
                if (field.type() != entry.getValue().type()) {
                    throw new PptGenerationException("Schema 字段类型与模板不匹配: " + entry.getKey());
                }
                if (field.required() && entry.getValue().text() == null
                        && entry.getValue().artifactId() == null && entry.getValue().value() == null) {
                    throw new PptGenerationException("Schema 必填字段为空: " + entry.getKey());
                }
            }
        }
    }

    private static void validateLegacy(PptSchema schema) {
        if (schema.titleText() == null || schema.titleText().isBlank()) {
            throw new PptGenerationException("PPT Schema 封面标题不能为空");
        }
        if (schema.contentSlides() == null || schema.contentSlides().isEmpty()) {
            throw new PptGenerationException("PPT Schema 至少需要一张内容页");
        }
        for (PptContentSlideFill slide : schema.contentSlides()) {
            if (slide == null || slide.slideTitleText() == null || slide.slideTitleText().isBlank()
                    || slide.slideBodyText() == null || slide.slideBodyText().isBlank()) {
                throw new PptGenerationException("PPT Schema 内容页标题和正文不能为空");
            }
        }
    }
}
