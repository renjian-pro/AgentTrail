package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.RunId;
import org.reactivestreams.Publisher;

public record AgentRunHandle(RunId runId, Publisher<AgentEvent> events) {
}
