package com.agenttrail.web.controller;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.web.service.ConversationHistoryService;
import com.agenttrail.web.dto.ConversationPageResponse;

import com.agenttrail.evaluation.GoldenCaseCandidateExtractor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Badcase 提升为 Golden Case 的浏览入口：先看最近的会话（跨全部用户，不是当前登录用户自己的
 * {@code /agent/v1/conversations}），挑一个会话拉出候选，前端据此填好 dimension/reference_sql/
 * assertions 后调 {@link GoldenCaseController#create} 落地成正式用例。这个 Controller 本身不写库。
 */
@RestController
public class GoldenCandidateController {
    private final ConversationHistoryService conversationHistoryService;
    private final GoldenCaseCandidateExtractor candidateExtractor;

    public GoldenCandidateController(ConversationHistoryService conversationHistoryService,
            GoldenCaseCandidateExtractor candidateExtractor) {
        this.conversationHistoryService = conversationHistoryService;
        this.candidateExtractor = candidateExtractor;
    }

    @GetMapping("/agent/v1/evaluation/conversations")
    @SaCheckPermission("golden:candidate:view")
    public ConversationPageResponse conversations(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return conversationHistoryService.findConversations(page, size);
    }

    @GetMapping("/agent/v1/evaluation/conversations/{conversationId}/candidates")
    @SaCheckPermission("golden:candidate:view")
    public List<GoldenCaseCandidateExtractor.GoldenCaseCandidate> candidates(@PathVariable String conversationId) {
        return candidateExtractor.extract(conversationId);
    }
}
