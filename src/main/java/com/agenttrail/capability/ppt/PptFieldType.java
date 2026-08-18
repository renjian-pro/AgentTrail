package com.agenttrail.capability.ppt;

/** Schema 字段的业务类型；它比 Python 渲染 payload 更稳定，不暴露渲染器内部结构。 */
public enum PptFieldType {
    TEXT,
    IMAGE,
    BACKGROUND,
    CHART,
    TABLE
}
