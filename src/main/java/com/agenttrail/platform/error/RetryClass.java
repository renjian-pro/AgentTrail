package com.agenttrail.platform.error;

public enum RetryClass {
    NONE,
    RETRIABLE,
    RATE_LIMITED,
    FATAL
}
