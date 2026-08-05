package com.agenttrail.web;

import com.agenttrail.loop.trace.TraceStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** 内部运维用的审计哈希链校验入口，不参与普通对话 API。 */
@RestController
public class TraceAuditController {

    private final TraceStore traceStore;

    public TraceAuditController(TraceStore traceStore) {
        this.traceStore = traceStore;
    }

    @GetMapping("/internal/audit/{conversationId}/verify")
    public TraceAuditVerificationResponse verify(@PathVariable String conversationId) {
        return traceStore.verifyChain(conversationId)
                .map(TraceAuditVerificationResponse::tamperedAt)
                .orElseGet(TraceAuditVerificationResponse::intact);
    }
}
