package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.PptGenerationService;
import com.agenttrail.capability.ppt.PptTask;
import com.agenttrail.capability.ppt.application.PptMessageRouter;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.web.dto.PptMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * PPT 的应用层入口：隐藏状态感知路由、幂等建档、澄清/取消、后台调度和会话结果记录。
 * Controller 只负责 HTTP 与鉴权，旧端点也应逐步委托这里，避免复制业务状态机。
 */
@Service
public class PptApplicationService {

    private static final Logger log = LoggerFactory.getLogger(PptApplicationService.class);

    private final PptGenerationService generationService;
    private final CapabilityConversationService conversationService;
    private final Executor executor;
    private final ConversationDigestService digestService;
    private final PptMessageRouter router;

    @Autowired
    public PptApplicationService(PptGenerationService generationService,
            CapabilityConversationService conversationService,
            @Qualifier("pptGenerationExecutor") Executor executor,
            ConversationDigestService digestService) {
        this(generationService, conversationService, executor, digestService, new PptMessageRouter());
    }

    PptApplicationService(PptGenerationService generationService,
            CapabilityConversationService conversationService,
            Executor executor, ConversationDigestService digestService, PptMessageRouter router) {
        this.generationService = generationService;
        this.conversationService = conversationService;
        this.executor = executor;
        this.digestService = digestService;
        this.router = router;
    }

    public Result handleMessage(String userId, PptMessageRequest request) {
        long startedAt = System.nanoTime();
        PptMessageRouter.Decision decision = routeMessage(userId, request);
        Optional<PptTask> latest = latestTask(userId, request.conversationId());
        String scopedUserId = userId == null ? "legacy" : userId;
        log.info("PPT message routed userIdHash={} conversationId={} taskId={} operation={} routeReason={} pipelineState={} runStatus={} messageLength={}",
                Integer.toHexString(scopedUserId.hashCode()), request.conversationId(), decision.taskId(), decision.action(), decision.reason(),
                latest.map(task -> task.status().name()).orElse("NONE"),
                latest.map(task -> task.runStatus().name()).orElse("NONE"), request.message().length());

        return switch (decision.action()) {
            case CANCEL -> {
                yield cancel(userId, requiredTaskId(decision));
            }
            case ANSWER -> {
                yield answer(userId, requiredTaskId(decision), request.message());
            }
            case RESUME -> {
                yield resume(userId, requiredTaskId(decision));
            }
            case MODIFY -> {
                long baseTaskId = requiredTaskId(decision);
                PptTask base = generationService.describe(scopedUserId, baseTaskId)
                        .orElseThrow(() -> new IllegalStateException("PPT 任务不存在: " + baseTaskId));
                long taskId;
                if (base.status() == com.agenttrail.capability.ppt.PptState.SUCCESS) {
                    taskId = generationService.prepareModify(scopedUserId, baseTaskId,
                            request.message(), request.idempotencyKey());
                } else {
                    generationService.requestCancel(baseTaskId);
                    taskId = generationService.prepareReplacement(scopedUserId, baseTaskId,
                            request.message(), request.idempotencyKey());
                }
                if (!generationService.consumeIdempotencyReplay(taskId)) {
                    schedule(userId, taskId, request.conversationId(), request.message(), startedAt);
                }
                yield new Result(taskId, decision.action(), true);
            }
            case CREATE -> {
                yield create(userId, request.conversationId(), request.message(), request.idempotencyKey());
            }
        };
    }

    /** 需求会话先只读取路由决定；CREATE 分支可在真正建任务前插入预检。 */
    public PptMessageRouter.Decision routeMessage(String userId, PptMessageRequest request) {
        return router.route(latestTask(userId, request.conversationId()), request.message());
    }

    private Optional<PptTask> latestTask(String userId, String conversationId) {
        String scopedUserId = userId == null ? "legacy" : userId;
        List<PptTask> tasks = generationService.describeConversation(scopedUserId, conversationId);
        return tasks.stream().findFirst();
    }

    /** 旧 /create 的兼容入口；语义始终是显式新建，不再偷偷调用旧关键词识别器。 */
    public Result create(String userId, String conversationId, String message, String idempotencyKey) {
        long startedAt = System.nanoTime();
        String scopedUserId = userId == null ? "legacy" : userId;
        String requirement = digestService.withContext(conversationId, message, "ppt");
        long taskId = generationService.prepareCreate(scopedUserId, conversationId, requirement, idempotencyKey);
        boolean replay = generationService.consumeIdempotencyReplay(taskId);
        if (!replay) schedule(userId, taskId, conversationId, message, startedAt);
        return new Result(taskId, PptMessageRouter.Action.CREATE, !replay);
    }

    public Result answer(String userId, long taskId, String answer) {
        String scopedUserId = userId == null ? "legacy" : userId;
        generationService.answerClarification(scopedUserId, taskId, answer);
        schedule(userId, taskId, null, null, System.nanoTime());
        return new Result(taskId, PptMessageRouter.Action.ANSWER, true);
    }

    public Result resume(String userId, long taskId) {
        String scopedUserId = userId == null ? "legacy" : userId;
        PptTask task = generationService.describe(scopedUserId, taskId)
                .orElseGet(() -> userId == null ? generationService.describe(taskId).orElse(null) : null);
        if (task == null) throw new IllegalArgumentException("PPT 任务不存在: " + taskId);
        if (task.status() == com.agenttrail.capability.ppt.PptState.AWAITING_INPUT
                || task.status() == com.agenttrail.capability.ppt.PptState.SUCCESS
                || task.status() == com.agenttrail.capability.ppt.PptState.CANCELLED) {
            throw new IllegalStateException("PPT 任务当前状态不支持继续: " + task.status());
        }
        schedule(userId, taskId, null, null, System.nanoTime());
        return new Result(taskId, PptMessageRouter.Action.RESUME, true);
    }

    public Result cancel(String userId, long taskId) {
        assertOwned(userId, taskId);
        generationService.requestCancel(taskId);
        return new Result(taskId, PptMessageRouter.Action.CANCEL, false);
    }

    public Result modify(String userId, long baseTaskId, String message, String idempotencyKey) {
        String scopedUserId = userId == null ? "legacy" : userId;
        long startedAt = System.nanoTime();
        long taskId = generationService.prepareModify(scopedUserId, baseTaskId, message, idempotencyKey);
        boolean replay = generationService.consumeIdempotencyReplay(taskId);
        if (!replay) schedule(userId, taskId, null, null, startedAt);
        return new Result(taskId, PptMessageRouter.Action.MODIFY, !replay);
    }

    private void assertOwned(String userId, long taskId) {
        Optional<PptTask> task = userId == null
                ? generationService.describe(taskId) : generationService.describe(userId, taskId);
        if (task.isEmpty()) throw new IllegalArgumentException("PPT 任务不存在: " + taskId);
    }

    private void schedule(String userId, long taskId, String conversationId, String message, long startedAt) {
        try {
            executor.execute(() -> {
                RuntimeException failure = null;
                try {
                    if (userId == null) generationService.run(taskId);
                    else generationService.run(userId, taskId);
                } catch (RuntimeException runFailure) {
                    failure = runFailure;
                    log.error("PPT background run failed taskId={} durationMs={}", taskId,
                            elapsedMillis(startedAt), runFailure);
                }
                long elapsed = elapsedMillis(startedAt);
                if (conversationId != null && message != null) {
                    if (failure == null) {
                        PptTask completed = generationService.describe(taskId).orElse(null);
                        String answer = "PPT 任务状态：" + (completed == null ? "UNKNOWN" : completed.status());
                        conversationService.recordSuccess(userId, conversationId, message,
                                answer, "ppt", java.util.Map.of("taskId", taskId), elapsed);
                    } else {
                        conversationService.recordFailure(userId, conversationId, message,
                                "ppt", failure.getMessage(), elapsed);
                    }
                }
                log.info("PPT background run finished taskId={} durationMs={} success={}",
                        taskId, elapsed, failure == null);
            });
            log.info("PPT task enqueued taskId={} conversationId={}", taskId, conversationId);
        } catch (RejectedExecutionException rejected) {
            generationService.markSchedulingFailure(taskId, "PPT 后台任务队列已满，请稍后重试");
            log.warn("PPT task enqueue rejected taskId={} conversationId={}", taskId, conversationId);
            throw rejected;
        }
    }

    private static long requiredTaskId(PptMessageRouter.Decision decision) {
        if (decision.taskId() == null) {
            throw new IllegalStateException("PPT operation " + decision.action() + " requires a task");
        }
        return decision.taskId();
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    public record Result(long taskId, PptMessageRouter.Action action, boolean scheduled) {
    }
}
