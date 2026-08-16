package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.deepresearch.DeepResearchReport;
import com.agenttrail.capability.deepresearch.DeepResearchService;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker;
import com.agenttrail.capability.deepresearch.InMemoryResearchArtifactStore;
import com.agenttrail.capability.deepresearch.DeepResearchWorkflow;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.ids.TaskId;
import com.agenttrail.runtime.repository.InMemoryCheckpointStore;
import com.agenttrail.runtime.repository.InMemoryRunEventStore;
import com.agenttrail.web.dto.DeepResearchRequest;
import com.agenttrail.web.dto.DeepResearchTaskResponse;
import com.agenttrail.web.service.CapabilityConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** HTTP adapter for the concrete DeepResearch workflow and shared task lifecycle. */
@RestController
public class DeepResearchController {
    private final DeepResearchTaskWorker worker;
    private final CapabilityConversationService conversationService;
    private final ConversationDigestService digestService;
    private final AtomicLong publicIds = new AtomicLong();
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    /** 多个构造函数并存时 Spring 无法自行选择，生产装配走这一个。 */
    @Autowired
    public DeepResearchController(DeepResearchTaskWorker worker,
            CapabilityConversationService conversationService,
            ConversationDigestService digestService) {
        this.worker = worker;
        this.conversationService = conversationService;
        this.digestService = digestService;
    }

    /** 兼容旧签名：不带摘要服务时退化成"不带上下文"，行为与 issue #103 之前一致。 */
    public DeepResearchController(DeepResearchTaskWorker worker,
            CapabilityConversationService conversationService) {
        this(worker, conversationService,
                new ConversationDigestService(null) {
                    @Override
                    public String withContext(String conversationId, String userMessage, String consumer) {
                        return userMessage;
                    }
                });
    }

    /** Compatibility constructor for direct callers while production uses the workflow worker bean. */
    public DeepResearchController(DeepResearchService service, CapabilityConversationService conversationService,
            @Qualifier("deepResearchExecutor") Executor executor) {
        this(new DeepResearchTaskWorker(
                new DeepResearchWorkflow(service, new InMemoryCheckpointStore(),
                        new InMemoryResearchArtifactStore(), new InMemoryRunEventStore()),
                new AgentTaskManager(), executor, new InMemoryRunEventStore()), conversationService);
    }

    @PostMapping("/agent/v1/deepresearch")
    public DeepResearchTaskResponse research(@Valid @RequestBody DeepResearchRequest request) {
        long publicId = publicIds.incrementAndGet();
        TaskId taskId = TaskId.of("deepresearch-" + publicId);
        String userId = currentUserId();
        Handle handle = new Handle(publicId, taskId, userId, request.conversationId(), request.question());
        handles.put(publicId, handle);
        try {
            // 已有会话里发起研究时带上上下文摘要（issue #103）；新会话原样发起
            DeepResearchTaskWorker.Submission submission = worker.submit(taskId, request.conversationId(),
                    digestService.withContext(request.conversationId(), request.question(), "deepresearch"),
                    request.previousQuestion(), request.previousClarifyingQuestion());
            submission.events().doOnComplete(() -> recordTerminal(handle)).subscribe();
            return response(handle);
        } catch (RuntimeException failure) {
            handles.remove(publicId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepResearch 后台任务无法提交", failure);
        }
    }

    @GetMapping("/agent/v1/deepresearch/{taskId}")
    public DeepResearchTaskResponse status(@PathVariable long taskId) {
        Handle handle = requireHandle(taskId);
        checkOwner(handle);
        return response(handle);
    }

    @PostMapping("/agent/v1/deepresearch/{taskId}/cancel")
    public DeepResearchTaskResponse cancel(@PathVariable long taskId) {
        Handle handle = requireHandle(taskId);
        checkOwner(handle);
        if (!worker.cancel(handle.internalTaskId)) {
            DeepResearchTaskResponse current = response(handle);
            if (!DeepResearchTaskResponse.RUNNING.equals(current.status())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "DeepResearch 任务已结束，无法取消: " + taskId);
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DeepResearch 任务尚未准备好取消: " + taskId);
        }
        return response(handle);
    }

    @GetMapping(value = "/agent/v1/deepresearch/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<com.agenttrail.platform.events.EventEnvelope>> events(@PathVariable long taskId,
            @RequestParam(name = "afterSequence", required = false, defaultValue = "0") long afterSequence,
            @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        Handle handle = requireHandle(taskId);
        checkOwner(handle);
        long sequence = Math.max(afterSequence, parseSequence(lastEventId));
        return worker.events(handle.internalTaskId, sequence)
                .map(event -> ServerSentEvent.<com.agenttrail.platform.events.EventEnvelope>builder(event)
                        .id(String.valueOf(event.sequence())).event(event.type()).build());
    }

    @GetMapping("/agent/v1/deepresearch/running")
    public List<Long> runningTaskIds() {
        String userId = currentUserId();
        return handles.values().stream()
                .filter(handle -> userId == null || userId.equals(handle.userId))
                .filter(handle -> DeepResearchTaskResponse.RUNNING.equals(response(handle).status()))
                .map(Handle::publicId).sorted().toList();
    }

    private DeepResearchTaskResponse response(Handle handle) {
        DeepResearchTaskWorker.TaskSnapshot snapshot = worker.snapshot(handle.internalTaskId);
        if (snapshot == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DeepResearch 任务不存在: " + handle.publicId);
        }
        return switch (snapshot.status()) {
            case RUNNING -> DeepResearchTaskResponse.running(handle.publicId, snapshot.currentStep());
            case SUCCESS -> DeepResearchTaskResponse.success(handle.publicId, snapshot.report(), snapshot.currentStep());
            case FAILED -> DeepResearchTaskResponse.failed(handle.publicId, snapshot.error(), snapshot.currentStep());
            case CANCELLED -> DeepResearchTaskResponse.cancelled(handle.publicId, snapshot.currentStep());
        };
    }

    private void recordTerminal(Handle handle) {
        if (!handle.recorded.compareAndSet(false, true)) return;
        DeepResearchTaskWorker.TaskSnapshot snapshot = worker.snapshot(handle.internalTaskId);
        if (snapshot == null) return;
        long elapsed = 0L;
        switch (snapshot.status()) {
            case SUCCESS -> {
                DeepResearchReport report = snapshot.report();
                String answer = report == null ? "" : (report.needsClarification()
                        ? report.clarifyingQuestion() : report.report());
                conversationService.recordSuccess(handle.userId, handle.conversationId, handle.question,
                        answer, "research", report, elapsed);
            }
            case FAILED -> conversationService.recordFailure(handle.userId, handle.conversationId, handle.question,
                    "research", snapshot.error(), elapsed);
            case CANCELLED -> conversationService.recordCancelled(handle.userId, handle.conversationId, handle.question,
                    "research", snapshot.currentStep(), elapsed);
            case RUNNING -> { }
        }
    }

    private Handle requireHandle(long publicId) {
        Handle handle = handles.get(publicId);
        if (handle == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                "DeepResearch 任务不存在: " + publicId);
        return handle;
    }

    private void checkOwner(Handle handle) {
        String userId = currentUserId();
        if (handle.userId != null && !handle.userId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DeepResearch 任务不存在: " + handle.publicId);
        }
    }

    private static long parseSequence(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return Long.parseLong(value); } catch (NumberFormatException ignored) { return 0; }
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }

    private static final class Handle {
        private final long publicId;
        private final TaskId internalTaskId;
        private final String userId;
        private final String conversationId;
        private final String question;
        private final AtomicBoolean recorded = new AtomicBoolean();

        private Handle(long publicId, TaskId internalTaskId, String userId, String conversationId, String question) {
            this.publicId = publicId;
            this.internalTaskId = internalTaskId;
            this.userId = userId;
            this.conversationId = conversationId;
            this.question = question;
        }

        private long publicId() { return publicId; }
    }
}
