package com.agenttrail.runtime;

/**
 * Domain boundary for the Runtime (see CONTEXT.md). No framework types may appear
 * in this interface's signature -- implementations live in framework-named adapter
 * packages (e.g. com.agenttrail.runtime.agentscope).
 */
public interface AgentRuntime {

    String respond(String userInput);
}
