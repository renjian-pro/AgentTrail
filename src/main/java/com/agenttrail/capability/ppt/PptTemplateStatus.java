package com.agenttrail.capability.ppt;

/** 模板版本的上线生命周期；只有 ACTIVE 版本可以被新任务选用。 */
public enum PptTemplateStatus {
    DRAFT,
    ACTIVE,
    DISABLED
}
