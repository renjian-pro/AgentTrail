package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * 动态 Schema 到渲染器之间的稳定页面载荷。它保留 pageId/pageType/templateRef/notes，
 * 这样渲染器不会把模型生成的任意 JSON 直接当成 Python 内部结构。
 */
public record PptDynamicPagePayload(String pageId, String pageType, String templatePageRef,
        List<PptTextFill> fills, String speakerNotes) {
}
