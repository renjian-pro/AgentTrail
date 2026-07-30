package com.agenttrail.loop.core;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/** Hand-rolled ReAct loop: one round = one streaming LLM call + (if requested) tool execution + recursion. */
public class AgentLoopExecutor {

    private final LlmInvoker llmInvoker;
    private final List<ToolCallback> tools;
    private final int maxRounds;

    public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds) {
        this.llmInvoker = new LlmInvoker(chatModel);
        this.tools = tools;
        this.maxRounds = maxRounds;
    }

    public Flux<AgentStreamEvent> stream(String question, RunnableParams params) {
        Sinks.Many<AgentStreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
        List<Message> messages = new ArrayList<>();
        messages.add(new UserMessage(question));

        scheduleRound(messages, sink, params);
        return sink.asFlux();
    }

    private void scheduleRound(List<Message> messages, Sinks.Many<AgentStreamEvent> sink, RunnableParams params) {
        RoundState state = new RoundState();

        llmInvoker.streamRound(messages, tools)
                .doOnNext(chunk -> processChunk(chunk, state))
                .doOnComplete(() -> finishRound(messages, state, sink, params))
                .doOnError(err -> sink.tryEmitNext(new AgentStreamEvent.Error("LLM_CALL_FAILED", err.getMessage())))
                .subscribe();
    }

    private void processChunk(ChatResponse chunk, RoundState state) {
        if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
            return;
        }
        AssistantMessage output = chunk.getResult().getOutput();

        if (output.hasToolCalls()) {
            state.mode = RoundMode.TOOL_CALL;
            state.toolCalls.addAll(output.getToolCalls());
            return;
        }

        String text = output.getText();
        if (text != null) {
            state.textBuffer.append(text);
        }
    }

    private void finishRound(List<Message> messages, RoundState state, Sinks.Many<AgentStreamEvent> sink, RunnableParams params) {
        if (state.mode == RoundMode.TEXT) {
            sink.tryEmitNext(new AgentStreamEvent.Text(state.textBuffer.toString()));
            sink.tryEmitNext(new AgentStreamEvent.Complete(params.conversationId()));
            sink.tryEmitComplete();
            return;
        }

        messages.add(AssistantMessage.builder().toolCalls(state.toolCalls).build());

        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : state.toolCalls) {
            sink.tryEmitNext(new AgentStreamEvent.ToolStart(toolCall.name(), toolCall.id(), toolCall.arguments()));
            String result = findTool(toolCall.name()).call(toolCall.arguments());
            sink.tryEmitNext(new AgentStreamEvent.ToolEnd(toolCall.name(), toolCall.id(), result));
            responses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result));
        }
        messages.add(ToolResponseMessage.builder().responses(responses).build());

        scheduleRound(messages, sink, params);
    }

    private ToolCallback findTool(String name) {
        return tools.stream()
                .filter(t -> t.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("unknown tool: " + name));
    }
}
