package com.agenttrail.capability.ppt.application;

/** PPT 需求会话的结果：未就绪只返回追问；就绪后才返回可提交给状态机的规范需求。 */
public record PptPreflightOutcome(
        boolean ready,
        String assistantMessage,
        String generationRequest) {
}
