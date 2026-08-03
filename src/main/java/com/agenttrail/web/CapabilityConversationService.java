package com.agenttrail.web;

import com.agenttrail.loop.persistence.TurnPersistenceHook;
import com.agenttrail.loop.persistence.TurnRecord;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import cn.dev33.satoken.stp.StpUtil;

/**
 * 把同步能力包的结果写回统一会话时间线。
 *
 * <p>不另建一张“能力历史”表：{@code agent_session} 才是用户会话的唯一索引。能力的结构化
 * 结果放进已有的 {@code timeline} JSON 列，{@code question}/{@code answer} 仍保留可读文本，
 * 因而侧栏分页、会话标题和后续模型历史都能继续复用同一套机制。
 */
public class CapabilityConversationService {

    private final TurnPersistenceHook persistenceHook;
    private final ObjectMapper objectMapper;

    public CapabilityConversationService(TurnPersistenceHook persistenceHook, ObjectMapper objectMapper) {
        this.persistenceHook = persistenceHook;
        this.objectMapper = objectMapper;
    }

    public Long recordSuccess(String conversationId, String question, String answer,
            String capability, Object payload, long totalResponseTimeMillis) {
        return recordSuccess(currentUserIdOrLegacy(), conversationId, question, answer, capability, payload, totalResponseTimeMillis);
    }

    public Long recordSuccess(String userId, String conversationId, String question, String answer,
            String capability, Object payload, long totalResponseTimeMillis) {
        return record(userId, conversationId, question, answer, capability, payload, null, totalResponseTimeMillis);
    }

    public Long recordFailure(String conversationId, String question, String capability,
            String error, long totalResponseTimeMillis) {
        return recordFailure(currentUserIdOrLegacy(), conversationId, question, capability, error, totalResponseTimeMillis);
    }

    public Long recordFailure(String userId, String conversationId, String question, String capability,
            String error, long totalResponseTimeMillis) {
        return record(userId, conversationId, question, null, capability, null, error, totalResponseTimeMillis);
    }

    private Long record(String userId, String conversationId, String question, String answer, String capability,
            Object payload, String error, long totalResponseTimeMillis) {
        try {
            // 对齐 dodo-agentx：timeline 根节点始终是 TimelineEntry[]，能力结果也作为
            // StageOutput 事件进入数组，而不是发明另一种根对象形状。
            String timeline = objectMapper.writeValueAsString(List.of(
                    new CapabilityStageOutput("StageOutput", capability, new CapabilityData(payload, error))));
            return persistenceHook.onTurnComplete(new TurnRecord(
                    conversationId, userId, question, answer, null, timeline, null, totalResponseTimeMillis));
        } catch (JsonProcessingException serializationFailure) {
            throw new IllegalStateException("能力结果无法序列化到会话时间线: " + capability, serializationFailure);
        }
    }

    private static String currentUserIdOrLegacy() {
        try { return StpUtil.isLogin() ? StpUtil.getLoginIdAsString() : "legacy"; }
        catch (RuntimeException noHttpContext) { return "legacy"; }
    }

    private record CapabilityStageOutput(String type, String stage, CapabilityData data) {
    }

    private record CapabilityData(Object payload, String error) {
    }
}
