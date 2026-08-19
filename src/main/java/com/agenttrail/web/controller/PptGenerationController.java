package com.agenttrail.web.controller;
import com.agenttrail.web.dto.PptClarifyRequest;
import com.agenttrail.web.dto.PptGenerationResponse;
import com.agenttrail.web.dto.PptGenerationRequest;
import com.agenttrail.web.dto.PptModifyRequest;
import com.agenttrail.web.dto.PptMessageRequest;
import com.agenttrail.web.service.CapabilityConversationService;
import com.agenttrail.web.service.PptApplicationService;
import com.agenttrail.web.service.PptTaskViewAssembler;

import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.capability.ppt.PptTask;
import com.agenttrail.capability.ppt.PptArtifact;
import com.agenttrail.capability.ppt.PptArtifactRef;
import com.agenttrail.capability.ppt.PptArtifactStore;
import cn.dev33.satoken.stp.StpUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
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
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.List;

/**
 * PPT 生成的 HTTP 入口（issue #24，异步化见后续 bug 修复）。{@code /create}/{@code /resume}
 * 只做"落库准备 + 把状态机丢到后台执行器"这一步就立即返回，真正的状态机推进
 * （{@link PptGenerationService#run}）在应用服务管理的后台执行器上跑；前端靠轮询
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
    private final PptArtifactStore artifactStore;
    private final PptApplicationService applicationService;
    private final PptTaskViewAssembler taskViewAssembler;

    /** 多个构造函数并存时 Spring 无法自行选择，生产装配走这一个。 */
    @Autowired
    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor,
            ConversationDigestService digestService, PptArtifactStore artifactStore,
            PptApplicationService applicationService, PptTaskViewAssembler taskViewAssembler) {
        this.pptGenerationService = pptGenerationService;
        this.artifactStore = artifactStore;
        this.applicationService = applicationService;
        this.taskViewAssembler = taskViewAssembler;
    }

    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor,
            ConversationDigestService digestService, PptArtifactStore artifactStore) {
        this(pptGenerationService, conversationService, pptGenerationExecutor, digestService, artifactStore,
                new PptApplicationService(pptGenerationService, conversationService,
                        pptGenerationExecutor, digestService), new PptTaskViewAssembler(pptGenerationService));
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
                }, null);
    }

    /** 单一会话写入口：回答、取消、继续、修改和新建均由应用层结合当前任务状态路由。 */
    @PostMapping("/agent/v1/ppt/message")
    public PptGenerationResponse message(@Valid @RequestBody PptMessageRequest request) {
        String userId = currentUserId();
        try {
            PptApplicationService.Result result = applicationService.handleMessage(userId, request);
            return toResponse(userId, result.taskId());
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }

    /** 兼容没有对象存储的旧测试/装配，仍支持本机 outputPath 下载。 */
    public PptGenerationController(PptGenerationService pptGenerationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor pptGenerationExecutor,
            ConversationDigestService digestService) {
        this(pptGenerationService, conversationService, pptGenerationExecutor, digestService, null);
    }

    @PostMapping("/agent/v1/ppt/create")
    public PptGenerationResponse create(@Valid @RequestBody PptGenerationRequest request) {
        String userId = currentUserId();
        try {
            long taskId = applicationService.create(userId, request.conversationId(), request.message(),
                    request.idempotencyKey()).taskId();
            return toResponse(userId, taskId);
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        }
    }

    @PostMapping("/agent/v1/ppt/resume/{taskId}")
    public PptGenerationResponse resume(@PathVariable long taskId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        try {
            applicationService.resume(userId, taskId);
            return toResponse(userId, taskId);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        } catch (IllegalStateException invalidState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalidState.getMessage(), invalidState);
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        }
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
            applicationService.answer(userId, taskId, request.answer());
            return toResponse(userId, taskId);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        } catch (IllegalStateException notAwaiting) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, notAwaiting.getMessage(), notAwaiting);
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        }
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
        try {
            applicationService.cancel(userId, taskId);
            return toResponse(userId, taskId);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        }
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
        final long newTaskId;
        try {
            newTaskId = applicationService.modify(userId, taskId, request.message(),
                    request.idempotencyKey()).taskId();
        } catch (IllegalArgumentException missingOrInvalid) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingOrInvalid.getMessage(), missingOrInvalid);
        } catch (IllegalStateException invalidState) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, invalidState.getMessage(), invalidState);
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        }
        return toResponse(userId, newTaskId);
    }

    @GetMapping("/agent/v1/ppt/running")
    public java.util.List<Long> runningTaskIds() {
        return pptGenerationService.runningTaskIdsFor(currentUserIdOrLegacyForDirectCall());
    }

    /**
     * 浏览器不能也不应直接访问服务器文件系统路径。只暴露任务号构成的受控下载地址，真实产物路径
     * 始终留在服务端；产物被清理或任务不存在时返回 404，而不是生成一个点击无反应的伪链接。
     */
    @GetMapping(value = "/agent/v1/ppt/{taskId}/download",
            produces = "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    public ResponseEntity<Resource> download(@PathVariable long taskId) {
        String userId = currentUserIdOrLegacyForDirectCall();
        Optional<PptTask> described = (userId == null ? pptGenerationService.describe(taskId)
                : pptGenerationService.describe(userId, taskId))
                ;
        // 仅保留无 HTTP 上下文的旧单测 seam；真实请求没有任务记录必须统一 404，不能退化成 IDOR。
        if (described.isEmpty() && userId != null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId);
        }
        PptTask task = described.orElse(null);
        com.agenttrail.capability.ppt.PptGenerationContext context = task == null ? null : safeContext(task);
        PptArtifactRef artifactRef = context == null ? null : context.artifactRef();
        if (artifactRef != null && artifactStore != null && !hasOutputFile(userId, taskId)) {
            return downloadFromArtifactStore(taskId, artifactRef);
        }
        Path output = outputFileOf(userId, taskId);
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

    /**
     * 多实例场景下本机临时 outputPath 可能已经被清理，回退到已校验归属的稳定 artifact 引用。
     * 对 MinIO 返回的 HTTPS 签名地址使用服务端 UrlResource 代理读取；本地实现则读取 file URI，
     * 签名地址本身不写入任务快照，也不返回给前端长期保存。
     */
    private ResponseEntity<Resource> downloadFromArtifactStore(long taskId, PptArtifactRef artifactRef) {
        try {
            String signedUrl = artifactStore.signedDownloadUrl(artifactRef.artifactId(), Duration.ofMinutes(5));
            Resource resource = new UrlResource(signedUrl);
            Optional<PptArtifact> artifact = artifactStore.find(artifactRef.artifactId());
            ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(artifactRef.contentType()))
                    .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                            .filename("ppt-" + taskId + ".pptx", StandardCharsets.UTF_8).build().toString());
            if (artifact.isPresent()) response.contentLength(artifact.get().sizeBytes());
            return response.body(resource);
        } catch (Exception failure) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 产物不可读取: " + taskId, failure);
        }
    }

    private PptGenerationResponse toResponse(String userId, long taskId) {
        return taskViewAssembler.response(userId, taskId);
    }

    private com.agenttrail.capability.ppt.PptGenerationContext safeContext(PptTask task) {
        try {
            return pptGenerationService.contextOf(task);
        } catch (RuntimeException malformed) {
            log.warn("PPT 任务 {} 上下文快照无法装配统一视图", task.id(), malformed);
            return null;
        }
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
