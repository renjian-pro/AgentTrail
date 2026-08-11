package com.agenttrail.runtime.tool;

import java.time.Duration;

public interface ToolResultPolicy {
    Duration timeout();

    String applyTo(String rawResult);
}
