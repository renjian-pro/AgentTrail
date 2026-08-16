package com.agenttrail.loop.pause;

import com.agenttrail.runtime.api.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link PauseState} 与 JSON 之间的双向转换，{@link JdbcPauseStateStore} 落地/读回整份快照要用。
 *
 * <p>只有这里需要把 {@code Message} 列表精确重建回具体类型——{@code MessageRendering}
 * （issue #17 TraceAudit 用）是单向渲染成人类可读文本，不需要、也没法反向解析回对象；
 * 这里因为要真的从快照恢复对话，必须重建原始消息，是完全独立的一套序列化逻辑。
 *
 * <p>不直接把 Jackson 丢给 {@link PauseState}/{@link RunnableParams}/{@link OutputType} 自动序列化：
 * 这几个类型要么没有无参构造函数，要么（{@code OutputType}）包了一个 {@code Class<?>}，
 * Jackson 默认不知道怎么处理。这里手工在"领域对象"和"通用 Map/List 结构"之间转换，
 * Jackson 只负责最外层的 Map/List ↔ JSON 字符串这一步，不需要理解任何自定义类型。
 */
final class PauseStateJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private PauseStateJson() {
    }

    static String toJson(PauseState state) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("conversationId", state.conversationId());
        root.put("messages", toMessageMaps(state.messages()));
        root.put("pendingToolCalls", toPendingToolCallMaps(state.pendingToolCalls()));
        root.put("reason", state.reason().name());
        root.put("safePoint", state.safePoint().name());
        root.put("question", state.question());
        root.put("params", toParamsMap(state.params()));
        root.put("roundAtPause", state.roundAtPause());
        root.put("pausedAtMillis", state.pausedAtMillis());
        try {
            return JSON.writeValueAsString(root);
        } catch (Exception serializationFailed) {
            throw new IllegalStateException(
                    "序列化会话 " + state.conversationId() + " 的暂停快照失败: " + serializationFailed.getMessage(),
                    serializationFailed);
        }
    }

    @SuppressWarnings("unchecked")
    static PauseState fromJson(String json) {
        try {
            Map<String, Object> root = JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
            List<Message> messages = fromMessageMaps((List<Map<String, Object>>) root.get("messages"));
            List<PendingToolCall> pendingToolCalls =
                    fromPendingToolCallMaps((List<Map<String, Object>>) root.get("pendingToolCalls"));
            RunnableParams params = fromParamsMap((Map<String, Object>) root.get("params"));
            return new PauseState(
                    (String) root.get("conversationId"),
                    messages,
                    pendingToolCalls,
                    PauseReason.valueOf((String) root.get("reason")),
                    SafePoint.valueOf((String) root.get("safePoint")),
                    (String) root.get("question"),
                    params,
                    ((Number) root.get("roundAtPause")).intValue(),
                    ((Number) root.get("pausedAtMillis")).longValue());
        } catch (Exception deserializationFailed) {
            throw new IllegalStateException(
                    "反序列化暂停快照失败: " + deserializationFailed.getMessage(), deserializationFailed);
        }
    }

    // ==================== messages ====================

    /** {@code ToolResponseMessage} 里可能一次带多个工具结果，展开成多条 role=tool 的记录。 */
    private static List<Map<String, Object>> toMessageMaps(List<Message> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage toolResponseMessage) {
                for (ToolResponseMessage.ToolResponse response : toolResponseMessage.getResponses()) {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("role", "tool");
                    map.put("toolCallId", response.id());
                    map.put("name", response.name());
                    map.put("content", response.responseData());
                    result.add(map);
                }
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("role", message.getMessageType().name().toLowerCase(Locale.ROOT));
            map.put("content", message.getText());
            if (message instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
                map.put("toolCalls", toToolCallMaps(assistantMessage.getToolCalls()));
            }
            result.add(map);
        }
        return result;
    }

    private static List<Map<String, Object>> toToolCallMaps(List<AssistantMessage.ToolCall> toolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", toolCall.id());
            map.put("type", toolCall.type());
            map.put("name", toolCall.name());
            map.put("arguments", toolCall.arguments());
            result.add(map);
        }
        return result;
    }

    /** 连续的 role=tool 记录要合并回同一个 {@code ToolResponseMessage}，和落库前展开的方式对称。 */
    @SuppressWarnings("unchecked")
    private static List<Message> fromMessageMaps(List<Map<String, Object>> raw) {
        List<Message> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        int i = 0;
        while (i < raw.size()) {
            Map<String, Object> item = raw.get(i);
            String role = (String) item.get("role");
            if ("tool".equals(role)) {
                List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
                while (i < raw.size() && "tool".equals(raw.get(i).get("role"))) {
                    Map<String, Object> toolItem = raw.get(i);
                    responses.add(new ToolResponseMessage.ToolResponse(
                            (String) toolItem.get("toolCallId"),
                            (String) toolItem.get("name"),
                            (String) toolItem.get("content")));
                    i++;
                }
                result.add(ToolResponseMessage.builder().responses(responses).build());
                continue;
            }
            result.add(parseSingleMessage(item, role));
            i++;
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Message parseSingleMessage(Map<String, Object> item, String role) {
        String content = (String) item.get("content");
        String safeContent = (content == null) ? "" : content;
        if ("assistant".equals(role)) {
            List<Map<String, Object>> toolCallMaps = (List<Map<String, Object>>) item.get("toolCalls");
            var builder = AssistantMessage.builder().content(safeContent);
            if (toolCallMaps != null && !toolCallMaps.isEmpty()) {
                builder.toolCalls(fromToolCallMaps(toolCallMaps));
            }
            return builder.build();
        }
        if ("system".equals(role)) {
            return new SystemMessage(safeContent);
        }
        return new UserMessage(safeContent);
    }

    private static List<AssistantMessage.ToolCall> fromToolCallMaps(List<Map<String, Object>> raw) {
        List<AssistantMessage.ToolCall> result = new ArrayList<>();
        for (Map<String, Object> map : raw) {
            result.add(new AssistantMessage.ToolCall(
                    (String) map.get("id"),
                    (String) map.get("type"),
                    (String) map.get("name"),
                    (String) map.get("arguments")));
        }
        return result;
    }

    // ==================== pending tool calls ====================

    private static List<Map<String, Object>> toPendingToolCallMaps(List<PendingToolCall> pendingToolCalls) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PendingToolCall pending : pendingToolCalls) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", pending.id());
            map.put("name", pending.name());
            map.put("arguments", pending.arguments());
            result.add(map);
        }
        return result;
    }

    private static List<PendingToolCall> fromPendingToolCallMaps(List<Map<String, Object>> raw) {
        List<PendingToolCall> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (Map<String, Object> map : raw) {
            result.add(new PendingToolCall(
                    (String) map.get("id"), (String) map.get("name"), (String) map.get("arguments")));
        }
        return result;
    }

    // ==================== params ====================

    /**
     * {@code outputType} 只存目标类型的类名——{@link OutputType} 本身不可序列化（包了一个
     * {@code Class<?>}），恢复时用 {@code Class.forName} 重建；同一个进程重启后类是否还在
     * classpath 上是恢复能否成功的前提，和"暂停跨版本发布恢复"这个更大的话题是同一类限制。
     */
    private static Map<String, Object> toParamsMap(RunnableParams params) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("conversationId", params.conversationId());
        map.put("userId", params.userId());
        map.put("toolParams", params.toolParams());
        if (params.outputType() != null) {
            map.put("outputTypeClassName", params.outputType().type().getName());
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static RunnableParams fromParamsMap(Map<String, Object> map) {
        String conversationId = (String) map.get("conversationId");
        String userId = (String) map.get("userId");
        Map<String, Object> toolParams = (Map<String, Object>) map.getOrDefault("toolParams", Map.of());
        String outputTypeClassName = (String) map.get("outputTypeClassName");
        OutputType outputType = null;
        if (outputTypeClassName != null) {
            try {
                outputType = OutputType.of(Class.forName(outputTypeClassName));
            } catch (ClassNotFoundException classNotFound) {
                throw new IllegalStateException(
                        "恢复暂停快照时找不到结构化输出的目标类型: " + outputTypeClassName, classNotFound);
            }
        }
        return new RunnableParams(conversationId, userId, toolParams, outputType);
    }
}
