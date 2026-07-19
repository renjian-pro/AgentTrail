package com.agenttrail.loop;

import java.util.List;

public interface LlmClient {

    LlmResponse call(List<ChatMessage> messages, List<ToolSpec> availableTools);
}
