package com.agenttrail.capability.ppt.application;

/** 任务创建前的需求草稿；字段保持可空，不能用默认值掩盖仍需用户确认的信息。 */
public record PptRequirementDraft(
        String title,
        String topic,
        String audience,
        int slideCount,
        String tone) {
}
