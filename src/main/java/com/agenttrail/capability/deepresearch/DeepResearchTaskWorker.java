package com.agenttrail.capability.deepresearch;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.TaskId;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Objects;
import java.util.concurrent.Executor;

/** Task adapter for DeepResearch. Cancellation is registered through the shared AgentTaskManager. */
public final class DeepResearchTaskWorker {
    private final DeepResearchWorkflow workflow;
    private final AgentTaskManager taskManager;
    private final Executor executor;

    public DeepResearchTaskWorker(DeepResearchWorkflow workflow, AgentTaskManager taskManager, Executor executor) {
        this.workflow = Objects.requireNonNull(workflow);
        this.taskManager = Objects.requireNonNull(taskManager);
        this.executor = Objects.requireNonNull(executor);
    }

    public Submission submit(String conversationId, String question) {
        TaskId taskId = TaskId.newId();
        Sinks.Many<EventEnvelope> events = Sinks.many().multicast().onBackpressureBuffer();
        Sinks.Many<AgentStreamEvent> cancellationStream = Sinks.many().multicast().onBackpressureBuffer();
        if (!taskManager.registerTask(conversationId, cancellationStream)) {
            throw new IllegalStateException("DeepResearch task already running for conversation: " + conversationId);
        }
        Disposable subscription = Mono.fromRunnable(() -> {
            try {
                workflow.run(taskId, question, event -> events.tryEmitNext(event));
                events.tryEmitComplete();
            } catch (Throwable failure) {
                events.tryEmitError(failure);
            } finally {
                taskManager.removeTask(conversationId);
            }
        }).subscribeOn(Schedulers.fromExecutor(executor)).subscribe();
        taskManager.setDisposable(conversationId, subscription);
        return new Submission(taskId, Flux.from(events.asFlux()), conversationId);
    }

    public boolean cancel(String conversationId) {
        return taskManager.stopTask(conversationId);
    }

    public record Submission(TaskId taskId, Flux<EventEnvelope> events, String conversationId) { }
}
