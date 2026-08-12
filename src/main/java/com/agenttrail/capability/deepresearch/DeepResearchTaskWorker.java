package com.agenttrail.capability.deepresearch;

import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.events.EventEnvelope;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.repository.RunEventStore;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/** Concrete DeepResearch task worker. Task lifecycle and cancellation are owned by AgentTaskManager. */
public final class DeepResearchTaskWorker {
    private final DeepResearchWorkflow workflow;
    private final AgentTaskManager taskManager;
    private final Executor executor;
    private final RunEventStore eventStore;
    private final Map<TaskId, TaskState> states = new ConcurrentHashMap<>();

    public DeepResearchTaskWorker(DeepResearchWorkflow workflow, AgentTaskManager taskManager, Executor executor) {
        this(workflow, taskManager, executor, null);
    }

    public DeepResearchTaskWorker(DeepResearchWorkflow workflow, AgentTaskManager taskManager, Executor executor,
            RunEventStore eventStore) {
        this.workflow = Objects.requireNonNull(workflow);
        this.taskManager = Objects.requireNonNull(taskManager);
        this.executor = Objects.requireNonNull(executor);
        this.eventStore = eventStore;
    }

    public Submission submit(String conversationId, String question) {
        return submit(TaskId.newId(), conversationId, question);
    }

    public Submission submit(TaskId taskId, String conversationId, String question) {
        return submit(taskId, conversationId, question, null, null);
    }

    public Submission submit(TaskId taskId, String conversationId, String question,
            String previousQuestion, String previousClarifyingQuestion) {
        TaskState state = new TaskState(taskId, conversationId, question);
        if (states.putIfAbsent(taskId, state) != null) {
            throw new IllegalStateException("DeepResearch task already exists: " + taskId.value());
        }
        Sinks.Many<EventEnvelope> events = Sinks.many().replay().all();
        state.events = events;
        Sinks.Many<AgentStreamEvent> cancellationStream = Sinks.many().multicast().onBackpressureBuffer();
        if (!taskManager.registerTask(conversationId, cancellationStream)) {
            states.remove(taskId);
            throw new IllegalStateException("DeepResearch task already running for conversation: " + conversationId);
        }
        Disposable subscription = Mono.fromRunnable(() -> {
            try {
                DeepResearchReport report = workflow.run(taskId, question, previousQuestion,
                        previousClarifyingQuestion, event -> {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new java.util.concurrent.CancellationException("DeepResearch task interrupted");
                    }
                    state.currentStep = event.payload();
                    events.tryEmitNext(event);
                });
                if (!state.cancelled) {
                    state.report = report;
                    state.status.set(DeepResearchTaskStatus.SUCCESS);
                    state.currentStep = "REPORT_ARTIFACT";
                }
            } catch (Throwable failure) {
                if (!state.cancelled && !(failure instanceof java.util.concurrent.CancellationException)) {
                    state.error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                    state.status.set(DeepResearchTaskStatus.FAILED);
                }
            } finally {
                taskManager.removeTask(conversationId);
                events.tryEmitComplete();
            }
        }).subscribeOn(Schedulers.fromExecutor(executor)).subscribe();
        state.subscription = subscription;
        taskManager.setDisposable(conversationId, subscription);
        return new Submission(taskId, events.asFlux(), conversationId);
    }

    public boolean cancel(TaskId taskId) {
        TaskState state = states.get(taskId);
        if (state == null || state.status.get() != DeepResearchTaskStatus.RUNNING) return false;
        state.cancelled = true;
        state.status.set(DeepResearchTaskStatus.CANCELLED);
        state.currentStep = state.currentStep == null ? "CANCELLED" : state.currentStep;
        taskManager.stopTask(state.conversationId);
        if (state.events != null) {
            EventEnvelope event = EventEnvelope.create(RunId.of(taskId.value()), taskId,
                    com.agenttrail.platform.ids.ConversationId.of("deepresearch:" + taskId.value()),
                    "ResearchCancelled", "deepresearch", EventEnvelope.Visibility.CLIENT, state.currentStep);
            EventEnvelope stored = eventStore == null ? event : eventStore.append(event);
            state.events.tryEmitNext(stored);
            state.events.tryEmitComplete();
        }
        return true;
    }

    public TaskSnapshot snapshot(TaskId taskId) {
        TaskState state = states.get(taskId);
        if (state == null) return null;
        return state.snapshot();
    }

    public Flux<EventEnvelope> events(TaskId taskId, long afterSequence) {
        TaskState state = states.get(taskId);
        if (state != null && state.events != null) {
            return state.events.asFlux().filter(event -> event.sequence() > afterSequence);
        }
        if (eventStore == null) return Flux.empty();
        return Flux.fromIterable(eventStore.afterSequence(RunId.of(taskId.value()), afterSequence));
    }

    public record Submission(TaskId taskId, Flux<EventEnvelope> events, String conversationId) { }

    public record TaskSnapshot(TaskId taskId, String conversationId, DeepResearchTaskStatus status,
            DeepResearchReport report, String error, String currentStep) { }

    public enum DeepResearchTaskStatus { RUNNING, SUCCESS, FAILED, CANCELLED }

    private static final class TaskState {
        private final TaskId taskId;
        private final String conversationId;
        private final String question;
        private final AtomicReference<DeepResearchTaskStatus> status =
                new AtomicReference<>(DeepResearchTaskStatus.RUNNING);
        private volatile DeepResearchReport report;
        private volatile String error;
        private volatile String currentStep = "CLARIFY";
        private volatile boolean cancelled;
        private volatile Sinks.Many<EventEnvelope> events;
        private volatile Disposable subscription;

        private TaskState(TaskId taskId, String conversationId, String question) {
            this.taskId = taskId;
            this.conversationId = conversationId;
            this.question = question;
        }

        private TaskSnapshot snapshot() {
            return new TaskSnapshot(taskId, conversationId, status.get(), report, error, currentStep);
        }
    }
}
