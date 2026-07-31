package com.agenttrail.web;

import com.agenttrail.loop.ppt.PptGenerationService;
import com.agenttrail.loop.ppt.PptTask;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * PPT 生成的 HTTP 入口（issue #24）。同步接口，和 {@code DeepResearchController} 一样——
 * 一次请求跑完整个状态机才返回，不做进度推送（那是这个"最小骨架"票之外的事）。
 *
 * <p>{@code /resume/{taskId}} 单独暴露断点续传：如果上一次 {@code /create} 因为某个状态失败
 * 而抛了异常，DB 里的任务停在那个失败的状态上，调用这个接口会从同一个状态重新跑，不是从头开始。
 */
@RestController
public class PptGenerationController {

    private final PptGenerationService pptGenerationService;

    public PptGenerationController(PptGenerationService pptGenerationService) {
        this.pptGenerationService = pptGenerationService;
    }

    @PostMapping("/agent/v1/ppt/create")
    public PptGenerationResponse create(@RequestBody PptGenerationRequest request) {
        long taskId = pptGenerationService.create(request.conversationId(), request.message());
        return toResponse(taskId);
    }

    @PostMapping("/agent/v1/ppt/resume/{taskId}")
    public PptGenerationResponse resume(@PathVariable long taskId) {
        pptGenerationService.run(taskId);
        return toResponse(taskId);
    }

    private PptGenerationResponse toResponse(long taskId) {
        PptTask task = pptGenerationService.describe(taskId)
                .orElseThrow(() -> new IllegalStateException("PPT 任务不存在: " + taskId));
        return new PptGenerationResponse(taskId, task.status(), task.errorMsg(),
                pptGenerationService.outputPathOf(taskId));
    }
}
