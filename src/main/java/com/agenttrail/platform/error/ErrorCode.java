package com.agenttrail.platform.error;

public record ErrorCode(String code, RetryClass retryClass) {
    public static final ErrorCode CONCURRENT_EXECUTION =
            new ErrorCode("CONCURRENT_EXECUTION", RetryClass.NONE);
    public static final ErrorCode PROMPT_INJECTION_DETECTED =
            new ErrorCode("PROMPT_INJECTION_DETECTED", RetryClass.FATAL);
    public static final ErrorCode LLM_CALL_FAILED =
            new ErrorCode("LLM_CALL_FAILED", RetryClass.RETRIABLE);
    public static final ErrorCode PAUSED = new ErrorCode("PAUSED", RetryClass.NONE);

    public static ErrorCode unknown(String code) {
        return new ErrorCode(code, RetryClass.RETRIABLE);
    }
}
