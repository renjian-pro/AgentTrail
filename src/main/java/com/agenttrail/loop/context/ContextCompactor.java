package com.agenttrail.loop.context;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

/**
 * 两层上下文压缩，在每轮模型调用前执行。类比 JVM 的分代回收：
 * <ul>
 *   <li><b>{@link #microCompact}</b> 像 Young GC——每轮都跑、零额外开销，
 *       只把"老而大"的工具内容换成占位符，消息结构完全不变
 *   <li><b>{@link #autoCompact}</b> 像 Full GC——只有真快撑爆上下文窗口时才触发，
 *       代价是一次额外的 LLM 摘要调用
 * </ul>
 *
 * <p>直接原地修改传入的消息列表，因为循环持有的就是这一个列表。
 */
public class ContextCompactor {

    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 摘要失败时保底保留的最近消息条数。 */
    private static final int FALLBACK_KEEP_MESSAGES = 10;

    private static final String SUMMARY_SYSTEM_PROMPT = """
            你是对话历史压缩助手。把给定的对话记录（含用户提问、模型回答、工具调用与工具结果）
            压缩成一段简明摘要，保留后续任务需要的关键事实、结论和进行中的状态，去掉过程性细节。
            直接输出摘要正文，不要任何额外解释。
            """;

    private final ContextPolicy policy;
    private final ChatModel chatModel;

    public ContextCompactor(ContextPolicy policy, ChatModel chatModel) {
        this.policy = policy;
        this.chatModel = chatModel;
    }

    /**
     * 按需压缩消息列表。
     *
     * @param messages        消息列表，会被原地修改
     * @param currentQuestion 当前用户提问，用来引导摘要该保留哪些信息
     */
    public void compact(List<Message> messages, String currentQuestion) {
        if (messages == null || messages.size() <= 2) {
            return;
        }
        microCompact(messages);

        int estimatedTokens = TokenEstimator.estimateTokens(messages);
        if (estimatedTokens > policy.tokenThreshold()) {
            log.info("上下文超出预算，触发整体摘要压缩: 估算 {} tokens > 阈值 {}，共 {} 条消息",
                    estimatedTokens, policy.tokenThreshold(), messages.size());
            autoCompact(messages, currentQuestion);
        }
    }

    // ==================== Layer 1: micro_compact ====================

    /**
     * 把较早的、过大的工具内容替换成占位符，消息结构和条数都不变。
     *
     * <p>压缩要同时作用在两个方向：工具的**返回值**会很大（一次查询几千行），
     * 模型发出的**调用参数**同样会很大（一大段 SQL、一整个脚本）。只压一边等于漏掉一半。
     *
     * <p>占位符必须是合法 JSON——它替换的内容位于 tool 消息里，格式不对模型会直接解析失败。
     * 这是真实踩过的生产 bug（踩坑点 #6）：写成自然语言的"[内容已省略]"当场炸掉。
     */
    private void microCompact(List<Message> messages) {
        compactOlderToolResults(messages);
        if (policy.maxToolLength() > 0) {
            compactOlderToolCallArguments(messages);
        }
    }

    private void compactOlderToolResults(List<Message> messages) {
        List<Integer> indices = indicesOf(messages, ToolResponseMessage.class::isInstance);
        for (int index : olderThanRecent(indices)) {
            ToolResponseMessage original = (ToolResponseMessage) messages.get(index);
            List<ToolResponseMessage.ToolResponse> compacted = new ArrayList<>();
            for (ToolResponseMessage.ToolResponse response : original.getResponses()) {
                compacted.add(compactResponse(response));
            }
            messages.set(index, ToolResponseMessage.builder().responses(compacted).build());
        }
    }

    private ToolResponseMessage.ToolResponse compactResponse(ToolResponseMessage.ToolResponse response) {
        String content = response.responseData();
        if (policy.isProtected(response.name()) || !exceedsLimit(content)) {
            return response;
        }
        return new ToolResponseMessage.ToolResponse(
                response.id(), response.name(), placeholder(response.name(), content.length(), "content compressed"));
    }

    private void compactOlderToolCallArguments(List<Message> messages) {
        List<Integer> indices = indicesOf(messages,
                message -> message instanceof AssistantMessage assistant && assistant.hasToolCalls());
        for (int index : olderThanRecent(indices)) {
            AssistantMessage original = (AssistantMessage) messages.get(index);
            List<AssistantMessage.ToolCall> compacted = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : original.getToolCalls()) {
                compacted.add(compactToolCall(toolCall));
            }
            messages.set(index, AssistantMessage.builder()
                    .content(original.getText())
                    .toolCalls(compacted)
                    .build());
        }
    }

    private AssistantMessage.ToolCall compactToolCall(AssistantMessage.ToolCall toolCall) {
        String arguments = toolCall.arguments();
        if (policy.isProtected(toolCall.name()) || !exceedsLimit(arguments)) {
            return toolCall;
        }
        return new AssistantMessage.ToolCall(toolCall.id(), toolCall.type(), toolCall.name(),
                placeholder(toolCall.name(), arguments.length(), "args compressed"));
    }

    // ==================== Layer 2: auto_compact ====================

    /**
     * 把除系统提示词以外的全部历史，换成一段 LLM 生成的摘要。
     *
     * <p>刻意"全压"而不是"保留最近 N 条 + 摘要更早的"：后者要处理摘要与保留部分的边界对齐，
     * 稍有不慎就会把一次工具调用和它的结果拆到边界两侧——模型看到一个没有结果的调用，
     * 或者一个没有来由的结果，反而更困惑（踩坑点 #7）。
     *
     * <p>系统提示词永远不进摘要：它定义了 Agent 的身份和行为规则，被摘要改写等于当场换了个 Agent。
     */
    private void autoCompact(List<Message> messages, String currentQuestion) {
        SystemMessage systemPrompt = (messages.get(0) instanceof SystemMessage first) ? first : null;
        int historyStart = (systemPrompt != null) ? 1 : 0;
        List<Message> history = new ArrayList<>(messages.subList(historyStart, messages.size()));

        String summary = summarise(history, currentQuestion);

        messages.clear();
        if (systemPrompt != null) {
            messages.add(systemPrompt);
        }
        messages.add(new UserMessage("[对话已压缩] 以下是之前对话的摘要：\n" + summary));
        log.info("已将 {} 条历史消息压缩为一段摘要", history.size());
    }

    /** 摘要本身要靠一次 LLM 调用，它同样可能失败——失败时降级为保留最近若干条，不能让整轮对话崩掉。 */
    private String summarise(List<Message> history, String currentQuestion) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                    new UserMessage(buildSummaryRequest(history, currentQuestion))));
            String summary = chatModel.call(prompt).getResult().getOutput().getText();
            return (summary != null) ? summary : truncationFallback(history);
        } catch (Exception summaryFailed) {
            log.warn("摘要生成失败，降级为保留最近 {} 条消息: {}", FALLBACK_KEEP_MESSAGES, summaryFailed.getMessage());
            return truncationFallback(history);
        }
    }

    private String buildSummaryRequest(List<Message> history, String currentQuestion) {
        return """
                ## 当前用户请求
                %s

                ## 对话记录
                %s
                """.formatted(
                (currentQuestion == null || currentQuestion.isBlank()) ? "无" : currentQuestion,
                renderConversation(history));
    }

    private String truncationFallback(List<Message> history) {
        int from = Math.max(0, history.size() - FALLBACK_KEEP_MESSAGES);
        return "...[摘要生成失败，以下为最近 %d 条对话内容]\n\n%s"
                .formatted(FALLBACK_KEEP_MESSAGES, renderConversation(history.subList(from, history.size())));
    }

    /**
     * 把消息渲染成纯文本喂给摘要模型。
     *
     * <p>这里**不做任何长度截断**：工具内容在 micro_compact 阶段该压的已经压过了，
     * 这里再截一刀会把"已经是占位符的内容"当成完整内容二次处理，摘要出来的结论跟事实对不上
     * （踩坑点 #8，真实生产 bug）。
     */
    private String renderConversation(List<Message> messages) {
        return MessageRendering.render(messages);
    }

    // ==================== 通用辅助 ====================

    private List<Integer> indicesOf(List<Message> messages, java.util.function.Predicate<Message> matching) {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (matching.test(messages.get(i))) {
                indices.add(i);
            }
        }
        return indices;
    }

    /** 掐掉末尾 {@code keepRecentTools} 个，剩下的才是"够老、可以压"的。 */
    private List<Integer> olderThanRecent(List<Integer> indices) {
        int keep = Math.min(policy.keepRecentTools(), indices.size());
        return indices.subList(0, indices.size() - keep);
    }

    private boolean exceedsLimit(String content) {
        return content != null && content.length() > policy.maxToolLength();
    }

    private String placeholder(String toolName, int originalLength, String message) {
        return "{\"compacted\":true,\"tool\":\"%s\",\"originalLength\":%d,\"message\":\"%s\"}"
                .formatted(toolName, originalLength, message);
    }
}
