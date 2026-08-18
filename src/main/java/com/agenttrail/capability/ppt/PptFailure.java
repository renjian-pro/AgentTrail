package com.agenttrail.capability.ppt;

import com.agenttrail.platform.error.RetryClass;

/**
 * PPT 任务可持久化、可脱敏的结构化失败。技术堆栈仍只进服务端日志，任务表只保存这些稳定字段，
 * 这样用户提示和自动重试不依赖 LLM 或供应商原始响应。
 */
public record PptFailure(
        String code,
        PptState failedStage,
        boolean retryable,
        RetryClass retryClass,
        int attempt,
        String userMessage,
        long occurredAtMillis) {

    public PptFailure {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("PPT failure code must not be blank");
        }
        if (failedStage == null || retryClass == null) {
            throw new IllegalArgumentException("PPT failure stage and retry class are required");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("PPT failure attempt must be positive");
        }
    }
}
