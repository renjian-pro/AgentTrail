package com.agenttrail.loop.persistence;

/**
 * 一轮问答的完整记录，落库用。
 *
 * @param conversationId    会话标识，跨轮不变
 * @param userId            提问用户
 * @param question          用户提问
 * @param answer            最终答案正文，不含思考过程
 * @param think             模型思考过程，与答案分开存
 * @param timeline          思考/正文/工具调用的时间线 JSON
 * @param firstResponseTime 首字延迟（毫秒），流式场景下用户真正感知的延迟指标
 * @param totalResponseTime 整轮总耗时（毫秒）
 */
public record TurnRecord(
        String conversationId,
        String userId,
        String question,
        String answer,
        String think,
        String timeline,
        Long firstResponseTime,
        Long totalResponseTime) {
}
