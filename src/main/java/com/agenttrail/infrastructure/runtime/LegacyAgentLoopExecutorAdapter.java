package com.agenttrail.infrastructure.runtime;

import com.agenttrail.loop.core.AgentCallException;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.error.ErrorCode;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.AgentEvent;
import com.agenttrail.runtime.api.AgentRequest;
import com.agenttrail.runtime.api.AgentResult;
import com.agenttrail.runtime.api.AgentRunHandle;
import com.agenttrail.runtime.api.AgentRunSnapshot;
import com.agenttrail.runtime.api.AgentRuntimeException;
import com.agenttrail.runtime.api.AgentRuntimePort;
import com.agenttrail.runtime.api.CancellationReason;
import com.agenttrail.runtime.api.ResumeCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.Objects;

public class LegacyAgentLoopExecutorAdapter implements AgentRuntimePort {
    private static final Logger log = LoggerFactory.getLogger(LegacyAgentLoopExecutorAdapter.class);

    private final AgentLoopExecutor delegate;
    private final AgentTaskManager taskManager;

    public LegacyAgentLoopExecutorAdapter(AgentLoopExecutor delegate, AgentTaskManager taskManager) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public AgentRunHandle start(AgentRequest request) {
        RunId runId = RunId.of(request.conversationId().value());
        Flux<AgentEvent> events = delegate.stream(request.message(), toRunnableParams(request))
                .map(event -> toAgentEvent(runId, request.conversationId(), event));
        return new AgentRunHandle(runId, events);
    }

    @Override
    public AgentResult call(AgentRequest request) {
        RunId runId = RunId.of(request.conversationId().value());
        try {
            return new AgentResult(runId, delegate.call(request.message(), toRunnableParams(request)));
        } catch (AgentCallException legacyFailure) {
            throw new AgentRuntimeException(mapErrorCode(legacyFailure.code()), legacyFailure.getMessage());
        }
    }

    @Override
    public AgentRunSnapshot snapshot(RunId runId) {
        throw new UnsupportedOperationException(
                "LegacyAgentLoopExecutorAdapter does not support snapshot: runId=" + runId);
    }

    @Override
    public void cancel(RunId runId, CancellationReason reason) {
        boolean stopped = taskManager.stopTask(runId.value());
        if (!stopped) {
            log.debug("cancel({}) found no running conversation (reason={})", runId, reason.description());
        }
    }

    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        ConversationId conversationId = ConversationId.of(runId.value());
        try {
            Flux<AgentEvent> events = delegate.resume(runId.value(), toResumeInstruction(command))
                    .map(event -> toAgentEvent(runId, conversationId, event));
            return new AgentRunHandle(runId, events);
        } catch (AgentCallException failure) {
            throw new AgentRuntimeException(mapErrorCode(failure.code()), failure.getMessage());
        }
    }

    private static RunnableParams toRunnableParams(AgentRequest request) {
        String userId = request.principal() == null ? null : request.principal().userId();
        return new RunnableParams(request.conversationId().value(), userId,
                request.toolParams(), request.outputType());
    }

    private static AgentEvent toAgentEvent(RunId runId, ConversationId conversationId,
                                           AgentStreamEvent legacy) {
        return switch (legacy) {
            case AgentStreamEvent.AgentStart ignored -> new AgentEvent.Started(runId, conversationId);
            case AgentStreamEvent.Text text -> new AgentEvent.TextDelta(runId, text.content());
            case AgentStreamEvent.Thinking thinking -> new AgentEvent.ThinkingDelta(runId, thinking.content());
            case AgentStreamEvent.ToolStart start ->
                    new AgentEvent.ToolStarted(runId, start.toolName(), start.toolCallId(), start.arguments());
            case AgentStreamEvent.ToolEnd end ->
                    new AgentEvent.ToolCompleted(runId, end.toolName(), end.toolCallId(), end.result());
            case AgentStreamEvent.Paused paused ->
                    new AgentEvent.Paused(runId, conversationId, paused.reason().name(),
                            paused.pendingTools());
            case AgentStreamEvent.Error error ->
                    new AgentEvent.Failed(runId, mapErrorCode(error.code()), error.message());
            case AgentStreamEvent.Complete complete ->
                    new AgentEvent.Completed(runId, conversationId, complete.turnId());
            case AgentStreamEvent.StageOutput ignored -> throw new UnsupportedOperationException(
                    "StageOutput has no AgentEvent variant yet");
            case AgentStreamEvent.TodoProgress ignored -> throw new UnsupportedOperationException(
                    "TodoProgress has no AgentEvent variant yet");
        };
    }

    private static ResumeInstruction toResumeInstruction(ResumeCommand command) {
        return switch (command) {
            case ResumeCommand.Approve ignored -> ResumeInstruction.ApprovalDecision.approve();
            case ResumeCommand.Reject reject -> ResumeInstruction.ApprovalDecision.reject(reject.reason());
            case ResumeCommand.WithNewInstruction instruction ->
                    new ResumeInstruction.NewInstruction(instruction.message());
        };
    }

    private static ErrorCode mapErrorCode(String legacyCode) {
        return switch (legacyCode) {
            case "CONCURRENT_EXECUTION" -> ErrorCode.CONCURRENT_EXECUTION;
            case "PROMPT_INJECTION_DETECTED" -> ErrorCode.PROMPT_INJECTION_DETECTED;
            case "LLM_CALL_FAILED" -> ErrorCode.LLM_CALL_FAILED;
            case "PAUSED" -> ErrorCode.PAUSED;
            default -> ErrorCode.unknown(legacyCode);
        };
    }
}
