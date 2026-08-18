package com.agenttrail.capability.ppt;

import java.util.List;

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
}
