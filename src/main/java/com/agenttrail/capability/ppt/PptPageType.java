package com.agenttrail.capability.ppt;

/** 动态 Schema 支持的页面语义类型；渲染器通过模板契约把语义映射成具体版式。 */
public enum PptPageType {
    COVER,
    CATALOG,
    SECTION,
    CONTENT,
    IMAGE_TEXT,
    COMPARE,
    TIMELINE,
    CHART,
    SUMMARY,
    END
}
