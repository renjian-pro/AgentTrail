package com.agenttrail.capability.ppt;

import java.util.List;
import java.util.LinkedHashMap;

/**
 * SCHEMA 状态的业务产出。旧版 title/content 字段继续支持当前默认模板；pages 是可扩展的动态
 * 语义层，渲染器必须先通过模板契约转换，不能让模型直接生成 Python 内部 payload。
 */
public record PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides,
        String coverImageUrl, List<PptPage> pages, String templateId, String templateVersion) {

    /** 兼容旧的四字段 Schema；动态页面/模板版本在旧任务中为空。 */
    public PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides,
            String coverImageUrl) {
        this(titleText, subtitleText, contentSlides, coverImageUrl, List.of(), null, null);
    }

    /** 兼容 issue #24 时期没有封面图的三字段调用点。 */
    public PptSchema(String titleText, String subtitleText, List<PptContentSlideFill> contentSlides) {
        this(titleText, subtitleText, contentSlides, null, List.of(), null, null);
    }

    @Override
    public List<PptPage> pages() {
        return pages == null ? List.of() : List.copyOf(pages);
    }

    public PptSchema withCoverImageUrl(String coverImageUrl) {
        return new PptSchema(titleText, subtitleText, contentSlides, coverImageUrl, pages(),
                templateId, templateVersion);
    }

    /** 把稳定图片地址写回指定页面字段；Schema 是图片提示词和渲染地址的唯一事实源。 */
    public PptSchema withImageArtifact(String pageId, String fieldName, String artifactUrl) {
        boolean[] matched = {false};
        List<PptPage> updatedPages = pages().stream().map(page -> {
            if (!page.pageId().equals(pageId)) return page;
            PptField current = page.fields().get(fieldName);
            if (current == null || current.type() != PptFieldType.IMAGE) {
                throw new PptGenerationException("PPT 图片字段不存在: " + pageId + "/" + fieldName);
            }
            LinkedHashMap<String, PptField> fields = new LinkedHashMap<>(page.fields());
            fields.put(fieldName, new PptField(PptFieldType.IMAGE, current.text(), artifactUrl, current.value()));
            matched[0] = true;
            return new PptPage(page.pageId(), page.pageType(), page.templatePageRef(), fields, page.speakerNotes());
        }).toList();
        if (!matched[0]) {
            throw new PptGenerationException("PPT 页面不存在: " + pageId);
        }
        return new PptSchema(titleText, subtitleText, contentSlides, coverImageUrl, updatedPages,
                templateId, templateVersion);
    }
}
