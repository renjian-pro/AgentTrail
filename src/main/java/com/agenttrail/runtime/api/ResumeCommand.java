package com.agenttrail.runtime.api;

public sealed interface ResumeCommand {
    record Approve() implements ResumeCommand {
    }

    record Reject(String reason) implements ResumeCommand {
    }

    record WithNewInstruction(String message) implements ResumeCommand {
    }
}
