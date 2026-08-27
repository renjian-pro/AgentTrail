package com.agenttrail.web.service;

import com.agenttrail.capability.ppt.application.PptMessageRouter;
import com.agenttrail.capability.ppt.application.PptPreflightOutcome;
import com.agenttrail.capability.ppt.application.PptRequirementPreflight;
import com.agenttrail.conversation.digest.ConversationDigestService;
import com.agenttrail.web.dto.PptMessageRequest;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * PPT 模式的单一会话入口。需求收集阶段只产生普通消息；确认完成或命中已有任务操作时才返回任务号。
 */
@Service
public final class PptConversationApplicationService {

    private final PptApplicationService tasks;
    private final PptRequirementPreflight preflight;
    private final ConversationDigestService digests;
    private final CapabilityConversationService conversations;

    public PptConversationApplicationService(PptApplicationService tasks,
            PptRequirementPreflight preflight,
            ConversationDigestService digests,
            CapabilityConversationService conversations) {
        this.tasks = tasks;
        this.preflight = preflight;
        this.digests = digests;
        this.conversations = conversations;
    }

    public Outcome handle(String userId, PptMessageRequest request) {
        PptMessageRouter.Decision decision = tasks.routeMessage(userId, request);
        if (decision.action() != PptMessageRouter.Action.CREATE) {
            PptApplicationService.Result result = tasks.handleMessage(userId, request);
            return new Outcome(result.taskId(), null);
        }

        long startedAt = System.nanoTime();
        String context = digests.withContext(request.conversationId(), request.message(), "ppt-preflight");
        PptPreflightOutcome assessed = preflight.assess(request.conversationId(), context);
        conversations.recordSuccess(userId, request.conversationId(), request.message(),
                assessed.assistantMessage(), "ppt-preflight", null,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        if (!assessed.ready()) {
            return new Outcome(null, assessed.assistantMessage());
        }

        PptApplicationService.Result created = tasks.create(userId, request.conversationId(),
                assessed.generationRequest(), request.idempotencyKey());
        return new Outcome(created.taskId(), assessed.assistantMessage());
    }

    public record Outcome(Long taskId, String assistantMessage) {
        public boolean hasTask() {
            return taskId != null;
        }
    }
}
