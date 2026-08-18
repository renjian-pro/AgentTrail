package com.agenttrail.capability.ppt;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面向业务的单页 Schema。pageId 是修改/重试/素材引用的稳定身份，不能用数组下标代替；
 * templatePageRef 只引用模板契约中的版式，不允许模型直接拼 Python payload。
 */
public record PptPage(String pageId, PptPageType pageType, String templatePageRef,
        Map<String, PptField> fields, String speakerNotes) {
    public PptPage {
        if (pageId == null || pageId.isBlank() || pageType == null
                || templatePageRef == null || templatePageRef.isBlank()) {
            throw new IllegalArgumentException("PPT page id, type and template reference are required");
        }
        fields = fields == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(fields));
    }
}
