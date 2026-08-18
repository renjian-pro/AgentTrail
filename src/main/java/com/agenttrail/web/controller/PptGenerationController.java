package com.agenttrail.web.controller;
import com.agenttrail.web.dto.PptClarifyRequest;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.agenttrail.web.dto.PptGenerationRequest;
import com.agenttrail.web.dto.PptModifyRequest;
import com.agenttrail.web.dto.PptTaskCapabilities;
import com.agenttrail.web.dto.PptTaskView;
import com.agenttrail.web.service.CapabilityConversationService;

import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.capability.ppt.PptTask;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.ArrayList;

/**
 * PPT 生成的 HTTP 入口（issue #24，异步化见后续 bug 修复）。{@code /create}/{@code /resume}
 * 只做"落库准备 + 把状态机丢到后台执行器"这一步就立即返回，真正的状态机推进
 * （{@link PptGenerationService#run}）在 {@link #pptGenerationExecutor} 上跑；前端靠轮询
 * {@code GET /agent/v1/ppt/{taskId}} 观察进度——状态机每完成一个状态就会落库一次
 * checkpoint（见 {@link PptGenerationService} 类注释），轮询天然就能看到逐步推进的效果，
 * 不需要另外搭一条 SSE 推送通道。
 *
 * <p>会话历史的记录时机也跟着从"请求返回时"挪到了"后台任务真正跑完时"——同步年代这两个
 * 时刻是同一时刻，异步之后不再是，记录必须等真正有结果（成功或失败）才发生，否则历史里
 * 存的只会是一句"正在运行中"，没有意义。
 */
@RestController
public class PptGenerationController {

    private static final Logger log = LoggerFactory.getLogger(PptGenerationController.class);

    private final PptGenerationService pptGenerationService;
    private final CapabilityConversationService conversationService;
    private final Executor pptGenerationExecutor;
    /**
     * 已有会话里发起 PPT 时带上上下文摘要（issue #103）。此前只传当次那一句话，用户说
     * "根据前面的会话做个 PPT" 会得到一份题为"前面的会话"的幻灯片，而且不报错。
     */
    private final ConversationDigestService digestService;

    /** 多个构造函数并存时 Spring 无法自行选择，生产装配走这一个。 */
    @Autowired
    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor,
            ConversationDigestService digestService) {
        this.pptGenerationService = pptGenerationService;
        this.conversationService = conversationService;
        this.pptGenerationExecutor = pptGenerationExecutor;
        this.digestService = digestService;
    }

    /** 兼容旧签名：不带摘要服务时退化成"不带上下文"，行为与 issue #103 之前一致。 */
    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor) {
        this(pptGenerationService, conversationService, pptGenerationExecutor,
                new ConversationDigestService(null) {
                    @Override
                    public String withContext(String conversationId, String userMessage, String consumer) {
                        return userMessage;
                    }
                });
    }

    /** 触发条件是会话状态（已有历史就带），不解析用户有没有说"根据前面的"——踩坑点 #52 的既有结论。 */
    private String withConversationContext(String conversationId, String message) {
        return digestService.withContext(conversationId, message, "ppt");
    }

    @PostMapping("/agent/v1/ppt/create")
    public PptGenerationResponse create(@Valid @RequestBody PptGenerationRequest request) {
        long startedAt = System.nanoTime();
        String userId = currentUserId();
        long taskId = prepareTask(userId, request);
        if (!pptGenerationService.consumeIdempotencyReplay(taskId)) {
            runInBackgroundThenRecord(userId, taskId, request.conversationId(), request.message(), startedAt);
        }
        return toResponse(userId, taskId);
    }

    @PostMapping("/agent/v1/ppt/resume/{taskId}")
    public PptGenerationResponse resume(@PathVariable long taskId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        PptTask task = (userId == null ? pptGenerationService.describe(taskId)
                : pptGenerationService.describe(userId, taskId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId));
        // WAITING_INPUT、SUCCESS、CANCELLED 都有专用动作或终态；普通 resume 不能越过业务边界。
        if (task.status() == com.agenttrail.capability.ppt.PptState.AWAITING_INPUT
                || task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                || task.status() == com.agenttrail.capability.ppt.PptState.CANCELLED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "PPT 任务当前状态不支持继续: " + task.status());
        }
        if (userId != null && pptGenerationService.describe(userId, taskId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId);
        }
        // /resume 原本就不记会话历史（只有 /create 记）——异步化不改变这一点，行为对齐旧实现。
        try {
            pptGenerationExecutor.execute(() -> runAndSwallow(userId, taskId));
        } catch (RejectedExecutionException rejected) {
            String message = "PPT 后台任务队列已满，请稍后重试";
            pptGenerationService.markSchedulingFailure(taskId, message);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, rejected);
        }
        return toResponse(userId, taskId);
    }

    /**
     * 用户回答澄清追问：把回答并回需求、把 checkpoint 推到 REQUIREMENT，再丢回后台继续跑。
     *
     * <p>和 {@code /resume} 的分工——{@code /resume} 是"从中断处再跑一遍当前状态"，不改需求；
     * 这里是"需求变了（补充了信息），从 REQUIREMENT 重新开始理解"。两者都落到同一个
     * {@link PptGenerationService#run} 上，区别只在调用前把 checkpoint 放到了哪儿。
     *
     * <p>任务没在等人时返回 409 而不是静默照做：那种情况下这句"回答"其实是一句新需求，
     * 悄悄拿它覆盖掉一条正在跑的任务，是比报错难查得多的一类问题。
     */
    @PostMapping("/agent/v1/ppt/clarify/{taskId}")
    public PptGenerationResponse clarify(@PathVariable long taskId, @Valid @RequestBody PptClarifyRequest request) {
        String userId = currentUserIdOrLegacyForDirectCall();
        try {
            pptGenerationService.answerClarification(userId, taskId, request.answer());
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        } catch (IllegalStateException notAwaiting) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, notAwaiting.getMessage(), notAwaiting);
        }
        try {
            pptGenerationExecutor.execute(() -> runAndSwallow(userId, taskId));
        } catch (RejectedExecutionException rejected) {
            String message = "PPT 后台任务队列已满，请稍后重试";
            pptGenerationService.markSchedulingFailure(taskId, message);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, rejected);
        }
        return toResponse(userId, taskId);
    }

    /** 轮询端点：不驱动任何执行，纯读当前 checkpoint——前端靠反复调用这个来看到生成进度推进。 */
    @GetMapping("/agent/v1/ppt/{taskId}")
    public PptGenerationResponse status(@PathVariable long taskId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        if (userId != null && pptGenerationService.describe(userId, taskId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId);
        }
        return toResponse(userId, taskId);
    }

    @PostMapping("/agent/v1/ppt/{taskId}/cancel")
    public PptGenerationResponse cancel(@PathVariable long taskId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        if (userId != null && pptGenerationService.describe(userId, taskId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId);
        }
        try {
            pptGenerationService.requestCancel(taskId);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        }
        return toResponse(userId, taskId);
    }

    /** 查询一个会话的全部版本；用户归属由服务层带入 SQL 条件，不能仅凭 conversationId 查询。 */
    @GetMapping("/agent/v1/ppt/conversation/{conversationId}")
    public List<PptGenerationResponse> conversationHistory(@PathVariable String conversationId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        String scopedUserId = userId == null ? "legacy" : userId;
        return pptGenerationService.describeConversation(scopedUserId, conversationId).stream()
                .map(task -> toResponse(userId, task.id()))
                .toList();
    }

    /** 批量状态查询用于会话历史一次刷新多张任务卡片；无权任务被统一过滤，避免 ID 枚举。 */
    @GetMapping("/agent/v1/ppt/tasks")
    public List<PptGenerationResponse> batchStatus(@RequestParam("ids") String ids) {
        String userId = currentUserIdOrLegacyForDirectCall();
        String scopedUserId = userId == null ? "legacy" : userId;
        List<Long> taskIds = parseTaskIds(ids);
        return pptGenerationService.describeMany(scopedUserId, taskIds).stream()
                .map(task -> toResponse(userId, task.id()))
                .toList();
    }

    /** 基于成功基线创建新的修改版本；重复幂等键只会重新返回原修改任务。 */
    @PostMapping("/agent/v1/ppt/{taskId}/modify")
    public PptGenerationResponse modify(@PathVariable long taskId,
            @Valid @RequestBody PptModifyRequest request) {
        String userId = currentUserIdOrLegacyForDirectCall();
        String scopedUserId = userId == null ? "legacy" : userId;
        final long newTaskId;
        try {
            newTaskId = pptGenerationService.prepareModify(scopedUserId, taskId, request.message(),
                    request.idempotencyKey());
        } catch (IllegalArgumentException missingOrInvalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingOrInvalid.getMessage(), missingOrInvalid);
        } catch (IllegalStateException invalidState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalidState.getMessage(), invalidState);
        }
        if (!pptGenerationService.consumeIdempotencyReplay(newTaskId)) {
            try {
                pptGenerationExecutor.execute(() -> runAndSwallow(userId, newTaskId));
            } catch (RejectedExecutionException rejected) {
                String message = "PPT 后台任务队列已满，请稍后重试";
                pptGenerationService.markSchedulingFailure(newTaskId, message);
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, message, rejected);
            }
        }
        return toResponse(userId, newTaskId);
    }

    @GetMapping("/agent/v1/ppt/running")
    public java.util.List<Long> runningTaskIds() {
        return pptGenerationService.runningTaskIdsFor(currentUserIdOrLegacyForDirectCall());
    }

    private void runInBackgroundThenRecord(String userId, long taskId, String conversationId, String message,
            long startedAt) {
        try {
            pptGenerationExecutor.execute(() -> {
            RuntimeException failure = null;
            try {
                if (userId == null) pptGenerationService.run(taskId); else pptGenerationService.run(userId, taskId);
            } catch (RuntimeException runFailure) {
                failure = runFailure;
            }
            long elapsed = elapsedMillis(startedAt);
            if (failure == null) {
                PptGenerationResponse response = toResponse(userId, taskId);
                String answer = "PPT 任务状态：" + response.status();
                conversationService.recordSuccess(userId, conversationId, message, answer, "ppt", response, elapsed);
            } else {
                conversationService.recordFailure(userId, conversationId, message, "ppt", failure.getMessage(), elapsed);
            }
            });
        } catch (RejectedExecutionException rejected) {
            String errorMessage = "PPT 后台任务队列已满，请稍后重试";
            pptGenerationService.markSchedulingFailure(taskId, errorMessage);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, errorMessage, rejected);
        }
    }

    /** {@code run(taskId)} 失败时已经在内部把 errorMsg 落库了（{@code taskStore.markFailed}）——
     * 轮询端点读得到；这里只需要不让异常从后台线程里裸抛出去，记一条日志留痕即可。 */
    private void runAndSwallow(String userId, long taskId) {
        try {
            if (userId == null) pptGenerationService.run(taskId); else pptGenerationService.run(userId, taskId);
        } catch (RuntimeException failure) {
            log.warn("PPT 任务 {} 后台续跑失败（已落库 errorMsg，轮询可见）", taskId, failure);
        }
    }

    /**
     * 浏览器不能也不应直接访问服务器文件系统路径。只暴露任务号构成的受控下载地址，真实产物路径
     * 始终留在服务端；产物被清理或任务不存在时返回 404，而不是生成一个点击无反应的伪链接。
     */
    @GetMapping(value = "/agent/v1/ppt/{taskId}/download",
            produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> download(@PathVariable long taskId) {
        Path output = outputFileOf(currentUserIdOrLegacyForDirectCall(), taskId);
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation"))
                    .contentLength(Files.size(output))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename(output.getFileName().toString(), StandardCharsets.UTF_8)
                            .build().toString())
                    .body(new FileSystemResource(output));
        } catch (IOException readFailure) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不可读取: " + taskId, readFailure);
        }
    }

    private PptGenerationResponse toResponse(String userId, long taskId) {
        PptTask task = (userId == null ? pptGenerationService.describe(taskId) : pptGenerationService.describe(userId, taskId))
                .orElseThrow(() -> new IllegalStateException("PPT 任务不存在: " + taskId));
        com.agenttrail.capability.ppt.PptGenerationContext context = safeContext(task);
        String downloadUrl = task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                && (context == null || context.artifactRef() == null) && hasOutputFile(userId, taskId)
                ? "/agent/v1/ppt/" + taskId + "/download"
                : null;
        if (task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS && context != null
                && context.artifactRef() != null) {
            // 具体签名/代理下载仍由 /download 端点处理；这里只暴露稳定 task URL，不把签名 URL 落进快照。
            downloadUrl = "/agent/v1/ppt/" + taskId + "/download";
        }
        PptTaskView view = buildTaskView(task, context);
        return new PptGenerationResponse(taskId, task.status(), task.errorMsg(), downloadUrl,
                pptGenerationService.pendingClarifyingQuestionOf(userId, taskId), view);
    }

    private com.agenttrail.capability.ppt.PptGenerationContext safeContext(PptTask task) {
        try {
            return pptGenerationService.contextOf(task);
        } catch (RuntimeException malformed) {
            log.warn("PPT 任务 {} 上下文快照无法装配统一视图", task.id(), malformed);
            return null;
        }
    }

    private PptTaskView buildTaskView(PptTask task, com.agenttrail.capability.ppt.PptGenerationContext context) {
        List<com.agenttrail.capability.ppt.PptCheckpointEvent> events;
        try {
            events = pptGenerationService.eventsOf(task.id());
        } catch (RuntimeException ignored) {
            events = List.of();
        }
        if (events == null) events = List.of();
        List<com.agenttrail.capability.ppt.PptState> completed = new ArrayList<>();
        for (var event : events) {
            if (com.agenttrail.capability.ppt.PptCheckpointEvent.OUTCOME_SUCCEEDED.equals(event.outcome())
                    && !completed.contains(event.stage())) completed.add(event.stage());
        }
        List<com.agenttrail.capability.ppt.PptState> stages = List.of(
                com.agenttrail.capability.ppt.PptState.INIT, com.agenttrail.capability.ppt.PptState.CLARIFY,
                com.agenttrail.capability.ppt.PptState.REQUIREMENT, com.agenttrail.capability.ppt.PptState.SEARCH,
                com.agenttrail.capability.ppt.PptState.TEMPLATE, com.agenttrail.capability.ppt.PptState.OUTLINE,
                com.agenttrail.capability.ppt.PptState.SCHEMA, com.agenttrail.capability.ppt.PptState.IMAGE,
                com.agenttrail.capability.ppt.PptState.RENDER, com.agenttrail.capability.ppt.PptState.VERIFY);
        int progress = task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS ? 100
                : Math.max(0, Math.min(99, (int) Math.round(100.0 * completed.size() / stages.size())));
        boolean terminal = task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                || task.status() == com.agenttrail.capability.ppt.PptState.CANCELLED;
        PptTaskCapabilities capabilities = new PptTaskCapabilities(
                !terminal && task.status() != com.agenttrail.capability.ppt.PptState.AWAITING_INPUT,
                task.status() != com.agenttrail.capability.ppt.PptState.SUCCESS
                        && task.status() != com.agenttrail.capability.ppt.PptState.CANCELLED
                        && task.status() != com.agenttrail.capability.ppt.PptState.AWAITING_INPUT,
                task.status() == com.agenttrail.capability.ppt.PptState.AWAITING_INPUT,
                task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                        && (context != null && context.artifactRef() != null || hasOutputFile(null, task.id())),
                task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS);
        com.agenttrail.capability.ppt.PptFailure failure = null;
        List<com.agenttrail.capability.ppt.PptWarning> warnings = List.of();
        if (context != null) {
            try { failure = pptGenerationService.failureOf(task); } catch (RuntimeException ignored) { }
            try { warnings = pptGenerationService.warningsOf(task); } catch (RuntimeException ignored) { }
        }
        return new PptTaskView(task.id(), task.conversationId(), context == null ? "CREATE" : context.operation(),
                task.status(), task.runStatus(),
                task.revision(), task.status() == com.agenttrail.capability.ppt.PptState.AWAITING_INPUT
                        ? "等待补充信息" : task.status().name(), completed, progress,
                context == null ? null : context.clarifyingQuestion(), failure, warnings,
                context == null ? null : context.artifactRef(), context == null ? null : context.baseTaskId(),
                context == null ? null : context.baseArtifactId(), task.createdAtMillis(),
                task.updatedAtMillis(), capabilities);
    }

    private long prepareTask(String userId, PptGenerationRequest request) {
        String scopedUserId = userId == null ? "legacy" : userId;
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            return pptGenerationService.prepare(scopedUserId, request.conversationId(),
                    withConversationContext(request.conversationId(), request.message()));
        }
        return pptGenerationService.prepare(scopedUserId, request.conversationId(),
                withConversationContext(request.conversationId(), request.message()),
                request.idempotencyKey());
    }

    private boolean hasOutputFile(String userId, long taskId) {
        try {
            String output = userId == null ? pptGenerationService.outputPathOf(taskId) : pptGenerationService.outputPathOf(userId, taskId);
            return Files.isRegularFile(Path.of(output));
        } catch (InvalidPathException | NullPointerException ignored) {
            return false;
        }
    }

    private static List<Long> parseTaskIds(String rawIds) {
        if (rawIds == null || rawIds.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 不能为空");
        }
        String[] parts = rawIds.split(",");
        if (parts.length > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "一次最多查询 100 个 PPT 任务");
        }
        try {
            return java.util.Arrays.stream(parts).map(String::trim).filter(value -> !value.isEmpty())
                    .map(Long::valueOf).filter(id -> id > 0).distinct().toList();
        } catch (NumberFormatException invalidId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ids 必须是逗号分隔的正整数", invalidId);
        }
    }

    private Path outputFileOf(String userId, long taskId) {
        String outputPath = userId == null ? pptGenerationService.outputPathOf(taskId) : pptGenerationService.outputPathOf(userId, taskId);
        try {
            Path output = outputPath == null ? null : Path.of(outputPath).toAbsolutePath().normalize();
            if (output == null || !Files.isRegularFile(output)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不存在: " + taskId);
            }
            return output;
        } catch (InvalidPathException invalidPath) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 文件不存在: " + taskId, invalidPath);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }

    /**
     * Direct controller calls in unit tests have no request context and retain the legacy
     * unscoped test seam. A real HTTP request without an authenticated principal is rejected
     * before any unscoped task lookup can occur; this closes the anonymous IDOR path.
     */
    private static String currentUserIdOrLegacyForDirectCall() {
        String userId = currentUserId();
        if (userId != null) return userId;
        if (RequestContextHolder.getRequestAttributes() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "需要登录后访问 PPT 任务");
        }
        return null;
    }
}
