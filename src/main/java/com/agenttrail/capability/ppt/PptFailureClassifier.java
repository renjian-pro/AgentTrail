package com.agenttrail.capability.ppt;

import com.agenttrail.platform.error.RetryClass;
import java.io.IOException;

/**
 * 把异常归一到有限的重试协议。分类只看异常类型和少量受控关键字，避免把供应商原始响应
 * 当成业务契约；未知异常默认不自动重试，交给结构化失败记录和人工排查。
 */
public final class PptFailureClassifier {
    private PptFailureClassifier() { }
    public static RetryClass classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
            if (message.contains("rate limit") || message.contains("too many requests")
                    || message.contains("http 429") || message.contains("status code 429")) {
                return RetryClass.RATE_LIMITED;
            }
            if (current instanceof PptRenderException || message.contains("timeout") || message.contains("timed out")) {
                return RetryClass.RETRIABLE;
            }
            if (message.contains("schema") || message.contains("template")) return RetryClass.FATAL;
            if (current instanceof IOException || message.contains("no space left")) return RetryClass.RETRIABLE;
            current = current.getCause();
        }
        return RetryClass.NONE;
    }
}
