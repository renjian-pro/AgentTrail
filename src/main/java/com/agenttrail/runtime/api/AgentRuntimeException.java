package com.agenttrail.runtime.api;

import com.agenttrail.platform.error.ErrorCode;

public class AgentRuntimeException extends RuntimeException {
    private final ErrorCode errorCode;

    public AgentRuntimeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
