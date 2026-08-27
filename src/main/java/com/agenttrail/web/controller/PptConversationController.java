package com.agenttrail.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agenttrail.web.dto.PptConversationResponse;
import com.agenttrail.web.dto.PptMessageRequest;
import com.agenttrail.web.service.PptConversationApplicationService;
import com.agenttrail.web.service.PptTaskViewAssembler;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.RejectedExecutionException;

/** PPT 模式专用会话入口；需求未确认时不创建任务。 */
@RestController
public final class PptConversationController {

    private final PptConversationApplicationService conversations;
    private final PptTaskViewAssembler taskViews;

    public PptConversationController(PptConversationApplicationService conversations,
            PptTaskViewAssembler taskViews) {
        this.conversations = conversations;
        this.taskViews = taskViews;
    }

    @PostMapping("/agent/v1/ppt/converse")
    public PptConversationResponse converse(@Valid @RequestBody PptMessageRequest request) {
        String userId = currentUserId();
        try {
            PptConversationApplicationService.Outcome outcome = conversations.handle(userId, request);
            if (!outcome.hasTask()) {
                return new PptConversationResponse("MESSAGE", outcome.assistantMessage(), null);
            }
            return new PptConversationResponse("TASK", outcome.assistantMessage(),
                    taskViews.response(userId, outcome.taskId()));
        } catch (RejectedExecutionException rejected) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "PPT 后台任务队列已满，请稍后重试", rejected);
        } catch (IllegalArgumentException missingTask) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, missingTask.getMessage(), missingTask);
        } catch (IllegalStateException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, conflict.getMessage(), conflict);
        }
    }

    private static String currentUserId() {
        try {
            return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : null;
        } catch (RuntimeException noHttpContext) {
            return null;
        }
    }
}
