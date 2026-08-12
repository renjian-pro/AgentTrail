package com.agenttrail.web.controller;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.agenttrail.web.dto.PptGenerationRequest;
import com.agenttrail.web.service.CapabilityConversationService;

import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptTask;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor) {
        this.pptGenerationService = pptGenerationService;
        this.conversationService = conversationService;
        this.pptGenerationExecutor = pptGenerationExecutor;
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
        String downloadUrl = task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                && hasOutputFile(userId, taskId)
                ? "/agent/v1/ppt/" + taskId + "/download"
                : null;
        return new PptGenerationResponse(taskId, task.status(), task.errorMsg(), downloadUrl);
    }

    private long prepareTask(String userId, PptGenerationRequest request) {
        String scopedUserId = userId == null ? "legacy" : userId;
        if (request.idempotencyKey() == null || request.idempotencyKey().isBlank()) {
            return pptGenerationService.prepare(scopedUserId, request.conversationId(), request.message());
        }
        return pptGenerationService.prepare(scopedUserId, request.conversationId(), request.message(),
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
