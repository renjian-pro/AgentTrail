package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;
import com.agenttrail.loop.deepresearch.DeepResearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.http.HttpStatus;

import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * DeepResearch 的 HTTP 入口（issue #25，异步化见后续 bug 修复）。{@code /agent/v1/deepresearch}
 * 只负责登记一个任务号、把真正的检索/综合报告丢到后台执行器上跑，立即返回；前端靠轮询
 * {@code GET /agent/v1/deepresearch/{taskId}} 观察是否跑完，和 PPT 那条轮询路径同一个模式。
 *
 * <p>和 PPT 不同的是这里的进度只有"跑完了没有"这一个粒度（{@link DeepResearchTaskRegistry}
 * 类注释里说明了原因）——不是逐阶段的 checkpoint，轮询只会看到 RUNNING 直接跳到 SUCCESS/
 * FAILED，不会有中间态。
 */
@RestController
public class DeepResearchController {

    private static final Logger log = LoggerFactory.getLogger(DeepResearchController.class);

    private final DeepResearchService deepResearchService;
    private final CapabilityConversationService conversationService;
    private final Executor deepResearchExecutor;
    private final DeepResearchTaskRegistry taskRegistry = new DeepResearchTaskRegistry();

    public DeepResearchController(DeepResearchService deepResearchService,
            CapabilityConversationService conversationService,
            @Qualifier("deepResearchExecutor") Executor deepResearchExecutor) {
        this.deepResearchService = deepResearchService;
        this.conversationService = conversationService;
        this.deepResearchExecutor = deepResearchExecutor;
    }

    @PostMapping("/agent/v1/deepresearch")
    public DeepResearchTaskResponse research(@RequestBody DeepResearchRequest request) {
        long startedAt = System.nanoTime();
        long taskId = taskRegistry.start();
        String userId = currentUserId();
        deepResearchExecutor.execute(() -> {
            try {
                DeepResearchReport report = deepResearchService.research(request.question());
                taskRegistry.complete(taskId, report);
                String answer = report.needsClarification() ? report.clarifyingQuestion() : report.report();
                if (userId == null) {
                    conversationService.recordSuccess(request.conversationId(), request.question(), answer,
                            "research", report, elapsedMillis(startedAt));
                } else {
                    conversationService.recordSuccess(userId, request.conversationId(), request.question(), answer,
                            "research", report, elapsedMillis(startedAt));
                }
            } catch (RuntimeException failure) {
                log.warn("DeepResearch 任务 {} 后台执行失败（已记入任务注册表，轮询可见）", taskId, failure);
                taskRegistry.fail(taskId, failure.getMessage());
                if (userId == null) {
                    conversationService.recordFailure(request.conversationId(), request.question(), "research",
                            failure.getMessage(), elapsedMillis(startedAt));
                } else {
                    conversationService.recordFailure(userId, request.conversationId(), request.question(),
                            "research", failure.getMessage(), elapsedMillis(startedAt));
                }
            }
        });
        return DeepResearchTaskResponse.running(taskId);
    }

    /** 轮询端点：不驱动任何执行，纯读内存里记的当前状态。 */
    @GetMapping("/agent/v1/deepresearch/{taskId}")
    public DeepResearchTaskResponse status(@PathVariable long taskId) {
        return taskRegistry.find(taskId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "DeepResearch 任务不存在（可能是应用重启丢失了进行中任务的记录）: " + taskId));
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }
}
