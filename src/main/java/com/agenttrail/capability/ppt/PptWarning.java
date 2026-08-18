package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * 成功但有降级时的用户可见提示。warning 不改变 SUCCEEDED 生命周期，也不保存供应商原始响应或
 * 完整思考链；affectedPageIds 让前端可以精确标出受影响页面。
 */
public record PptWarning(String code, PptState stage, String userMessage, List<String> affectedPageIds) {
    public PptWarning {
        if (code == null || code.isBlank() || stage == null || userMessage == null || userMessage.isBlank()) {
            throw new IllegalArgumentException("PPT warning code, stage and message are required");
        }
        affectedPageIds = affectedPageIds == null ? List.of() : List.copyOf(affectedPageIds);
    }
}
