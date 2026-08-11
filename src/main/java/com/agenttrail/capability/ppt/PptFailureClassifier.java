package com.agenttrail.capability.ppt;

import com.agenttrail.platform.error.RetryClass;
import java.io.IOException;

public final class PptFailureClassifier {
    private PptFailureClassifier() { }
    public static RetryClass classify(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
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
