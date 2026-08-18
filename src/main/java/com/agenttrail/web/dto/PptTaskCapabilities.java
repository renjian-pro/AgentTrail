package com.agenttrail.web.dto;

/** 后端根据归属、生命周期和产物计算的操作能力；前端不再复制状态机按钮规则。 */
public record PptTaskCapabilities(boolean canCancel, boolean canResume, boolean canAnswer,
        boolean canDownload, boolean canModify) {
}
