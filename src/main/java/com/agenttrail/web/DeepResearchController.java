package com.agenttrail.web;

import com.agenttrail.loop.deepresearch.DeepResearchReport;
import com.agenttrail.loop.deepresearch.DeepResearchService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import cn.dev33.satoken.stp.StpUtil;

import java.util.concurrent.TimeUnit;

/**
 * DeepResearch 的 HTTP 入口（issue #25）。同步接口——需求澄清→主题生成→逐个任务检索→
 * 综合报告全部跑完才返回，符合这一票"最小骨架"的定位（先不做流式进度推送）。
 */
@RestController
public class DeepResearchController {

    private final DeepResearchService deepResearchService;
    private final CapabilityConversationService conversationService;

    public DeepResearchController(DeepResearchService deepResearchService,
            CapabilityConversationService conversationService) {
        this.deepResearchService = deepResearchService;
        this.conversationService = conversationService;
    }

    @PostMapping("/agent/v1/deepresearch")
    public DeepResearchReport research(@RequestBody DeepResearchRequest request) {
        long startedAt = System.nanoTime();
        try {
            DeepResearchReport report = deepResearchService.research(request.question());
            String answer = report.needsClarification() ? report.clarifyingQuestion() : report.report();
            String userId = currentUserId();
            if (userId == null) {
                conversationService.recordSuccess(request.conversationId(), request.question(), answer,
                        "research", report, elapsedMillis(startedAt));
            } else {
                conversationService.recordSuccess(userId, request.conversationId(), request.question(), answer,
                        "research", report, elapsedMillis(startedAt));
            }
            return report;
        } catch (RuntimeException failure) {
            String userId = currentUserId();
            if (userId == null) {
                conversationService.recordFailure(request.conversationId(), request.question(), "research",
                        failure.getMessage(), elapsedMillis(startedAt));
            } else {
                conversationService.recordFailure(userId, request.conversationId(), request.question(), "research",
                        failure.getMessage(), elapsedMillis(startedAt));
            }
            throw failure;
        }
    }

    private static long elapsedMillis(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private static String currentUserId() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null; }
        catch (RuntimeException noHttpContext) { return null; }
    }
}
