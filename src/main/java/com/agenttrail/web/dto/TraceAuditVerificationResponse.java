package com.agenttrail.web.dto;

/** 审计链校验结果；{@code atRound} 仅在发现篡改时返回。 */
public record TraceAuditVerificationResponse(boolean tampered, Integer atRound) {

    public static TraceAuditVerificationResponse intact() {
        return new TraceAuditVerificationResponse(false, null);
    }

    public static TraceAuditVerificationResponse tamperedAt(int round) {
        return new TraceAuditVerificationResponse(true, round);
    }
}
