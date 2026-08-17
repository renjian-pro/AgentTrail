package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.capability.deepresearch.DeepResearchReport;
import com.agenttrail.capability.deepresearch.DeepResearchService;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker;
import com.agenttrail.capability.deepresearch.DeepResearchTaskWorker.DeepResearchTaskStatus;
import com.agenttrail.capability.deepresearch.InMemoryResearchArtifactStore;
import com.agenttrail.capability.deepresearch.InMemoryResearchTaskRecordStore;
import com.agenttrail.capability.deepresearch.ResearchTaskRecord;
import com.agenttrail.capability.deepresearch.ResearchTaskRecordStore;
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

/** HTTP adapter for the concrete DeepResearch workflow and shared task lifecycle. */
@RestController
public class DeepResearchController {
    private final DeepResearchTaskWorker worker;
    private final CapabilityConversationService conversationService;
    private final ConversationDigestService digestService;
    /**
     * 任务元信息（issue #108 / R20）。**taskId 由它的自增主键分配**，不再用进程内的
     * {@code AtomicLong}——那个重启后从 0 重来，新任务会复用旧编号。
     */
    private final ResearchTaskRecordStore records;
    /**
     * 运行时句柄，只活在当前进程里：{@code Future}、SSE 事件流这些东西本来就没法持久化。
     * 重启后这张表是空的，而 {@link #records} 里还留着那些任务——两者的差集就是"被重启打断的
     * 任务"，由启动扫描统一标成失败（见 {@code DeepResearchConfig}）。
     */
    private final Map<Long, Handle> handles = new ConcurrentHashMap<>();

    /** 多个构造函数并存时 Spring 无法自行选择，生产装配走这一个。 */
    @Autowired
    public DeepResearchController(DeepResearchTaskWorker worker,
            CapabilityConversationService conversationService,
            ConversationDigestService digestService,
            ResearchTaskRecordStore records) {
        this.worker = worker;
        this.conversationService = conversationService;
        this.digestService = digestService;
        this.records = records;
    }

    /** 兼容旧签名：不带任务元信息存储时退化成纯内存，行为与 issue #108 之前一致。 */
    public DeepResearchController(DeepResearchTaskWorker worker,
            CapabilityConversationService conversationService,
            ConversationDigestService digestService) {
        this(worker, conversationService, digestService, new InMemoryResearchTaskRecordStore());
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
        String userId = currentUserId();
        // 先落库拿到 taskId：自增主键保证重启后不复用编号，也让这个任务在进程没了之后仍然
        // 存在过（issue #108）。
        long publicId = records.create(userId, request.conversationId(), request.question());
        TaskId taskId = TaskId.of("deepresearch-" + publicId);
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
            // 提交失败也要落终态：那一行已经建出来了，留着 RUNNING 会被启动扫描误判成"被重启打断"
            records.markTerminal(publicId, DeepResearchTaskStatus.FAILED, "后台任务无法提交");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "DeepResearch 后台任务无法提交", failure);
        }
    }

    @GetMapping("/agent/v1/deepresearch/{taskId}")
    public DeepResearchTaskResponse status(@PathVariable long taskId) {
        Handle handle = handles.get(taskId);
        if (handle == null) {
            // 内存里没有，但库里可能有——这正是重启之后的形态。返回它的真实终态，
            // 而不是 404（"从来不存在"和事实正好相反，见 issue #108）。
            return persistedResponse(taskId);
        }
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

    /**
     * 走 {@link ResearchTaskRecordStore}，不扫内存 {@code handles}（issue #108）——重启后那张 map
     * 是空的而库里还留着记录，扫内存会给出和 {@code status()} 互相矛盾的答案，而那恰恰是 R20
     * 要消除的场景。{@code PptGenerationController#runningTaskIds} 同样是委托给 store。
     */
    @GetMapping("/agent/v1/deepresearch/running")
    public List<Long> runningTaskIds() {
        return records.runningIdsFor(currentUserId());
    }

    /**
     * 内存里没有句柄时的回答（issue #108 / R20）：查库。
     *
     * <p>三种结果对应三件不同的事，不能混成同一个 404：
     * <ul>
     *   <li>库里也没有 —— 这个 taskId 确实从来不存在，404 是对的
     *   <li>库里是终态 —— 任务跑完过，只是句柄随进程没了。报告正文在
     *       {@code agent_session.timeline} 里（历史回放读得到），这里返回状态即可
     *   <li>库里还是 RUNNING —— 启动扫描本该把它标成失败，走到这里说明扫描没跑或刚好并发。
     *       就地按"被重启打断"处理，绝不返回 RUNNING —— 那会让前端永远轮询一个不会变的状态
     * </ul>
     */
    private DeepResearchTaskResponse persistedResponse(long taskId) {
        // resolveStale 顺手把漏网的 RUNNING（启动扫描没跑，或刚好并发）就地标成被打断——
        // INTERRUPTED_BY_RESTART 的构造只在 store 里一处，这里不再自己拼终态
        ResearchTaskRecord record = records.resolveStale(taskId).orElseThrow(() -> notFound(taskId));
        String userId = currentUserId();
        if (record.userId() != null && !record.userId().equals(userId)) {
            // 和 checkOwner 一样用 404 而不是 403：403 会泄漏"这个 id 存在"
            throw notFound(taskId);
        }
        return switch (record.status()) {
            case CANCELLED -> DeepResearchTaskResponse.cancelled(taskId, null);
            case FAILED -> DeepResearchTaskResponse.failed(taskId, record.errorMsg(), null);
            // 报告正文不在这张表里（见 ResearchTaskRecord 的说明），历史回放走 agent_session.timeline
            case SUCCESS -> DeepResearchTaskResponse.success(taskId, null, null);
            // resolveStale 已经把 RUNNING 推成终态了，走到这里只可能是并发下的极窄窗口
            case RUNNING -> DeepResearchTaskResponse.failed(
                    taskId, ResearchTaskRecord.INTERRUPTED_BY_RESTART, null);
        };
    }

    private static ResponseStatusException notFound(long taskId) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "DeepResearch 任务不存在: " + taskId);
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
                // 报告正文进 agent_session.timeline（这是历史回放读的那一份，issue #103）
                conversationService.recordSuccess(handle.userId, handle.conversationId, handle.question,
                        answer, "research", report, elapsed);
                // research_task 只记状态，不复制正文——同一个产物存两处必然漂移
                records.markTerminal(handle.publicId, DeepResearchTaskStatus.SUCCESS, null);
            }
            case FAILED -> {
                conversationService.recordFailure(handle.userId, handle.conversationId, handle.question,
                        "research", snapshot.error(), elapsed);
                records.markTerminal(handle.publicId, DeepResearchTaskStatus.FAILED, snapshot.error());
            }
            case CANCELLED -> {
                conversationService.recordCancelled(handle.userId, handle.conversationId, handle.question,
                        "research", snapshot.currentStep(), elapsed);
                records.markTerminal(handle.publicId, DeepResearchTaskStatus.CANCELLED, null);
            }
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
