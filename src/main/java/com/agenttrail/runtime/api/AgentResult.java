package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.RunId;

public record AgentResult(RunId runId, String text) {
}
