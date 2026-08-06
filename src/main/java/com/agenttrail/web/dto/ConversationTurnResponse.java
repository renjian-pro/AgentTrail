package com.agenttrail.web.dto;

/** 前端历史列表需要的最小单轮快照，思考和工具时间线保留给展开详情使用。 */
public record ConversationTurnResponse(long id, String question, String answer, String think, String timeline,
        long createdAtMillis) {
}
