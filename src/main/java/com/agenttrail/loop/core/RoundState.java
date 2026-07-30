package com.agenttrail.loop.core;

import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

/** Mutable accumulator for a single round's streamed chunks. */
class RoundState {
    RoundMode mode = RoundMode.TEXT;
    final StringBuilder textBuffer = new StringBuilder();
    final List<AssistantMessage.ToolCall> toolCalls = Collections.synchronizedList(new ArrayList<>());
}
