package com.agenttrail.loop.model;

/**
 * Unified streaming event protocol emitted by the hand-rolled ReAct loop.
 * Variants are added as the corresponding mechanism lands (see roadmap Phase 0 tickets).
 */
public sealed interface AgentStreamEvent {

    record Text(String content) implements AgentStreamEvent {
    }

    record ToolStart(String toolName, String toolCallId, String arguments) implements AgentStreamEvent {
    }

    record ToolEnd(String toolName, String toolCallId, String result) implements AgentStreamEvent {
    }

    record Error(String code, String message) implements AgentStreamEvent {
    }

    record Complete(String conversationId) implements AgentStreamEvent {
    }
}
