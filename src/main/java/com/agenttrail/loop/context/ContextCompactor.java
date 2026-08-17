package com.agenttrail.loop.context;

import com.agenttrail.loop.prompt.PromptRegistry;
import com.agenttrail.loop.core.SynchronousLlmCall;
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
 * <p>两层之前还有一步和 GC 类比无关的结构性清理——{@link #dropSupersededMarkedMessages}
 * （issue #37）：按 {@link ContextPolicy#retainLatestOnlyMarkers()} 删掉"已经被更新一条取代"
 * 的旧消息，不是压缩内容大小，是整条丢弃过期内容，默认不启用（标记集合为空）。
 *
 * <p>直接原地修改传入的消息列表，因为循环持有的就是这一个列表。
 */
public class ContextCompactor {
    /** 提示词正文外置在 {@code resources/prompts/}（issue #100）：改一句不用动代码，
     *  且每一版都有可写进 trace 的 {@code id@version#hash} 标识，Golden 分数变化才归因得了。 */
    private static final PromptRegistry PROMPTS = PromptRegistry.loadFromClasspath();


    private static final Logger log = LoggerFactory.getLogger(ContextCompactor.class);

    /** 摘要失败时保底保留的最近消息条数。 */
    private static final int FALLBACK_KEEP_MESSAGES = 10;

    private static final String SUMMARY_SYSTEM_PROMPT = PROMPTS.text("runtime.context_compaction");

    /** 这一版摘要提示词的 {@code id@version#hash}，压缩真的跑过时由调用方记进 trace（issue #101）。 */
    public static String promptStamp() {
        return PROMPTS.get("runtime.context_compaction").stamp();
    }

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
        if (messages == null) {
            return;
        }
        // 标记去重要在两层压缩之前、且不受"消息数太少不值得压"这道门槛限制——它不是省 token
        // 的优化，是纠正"过期内容不该继续参与决策"这个语义问题，哪怕总共只有两三条消息也要生效。
        dropSupersededMarkedMessages(messages);
        if (messages.size() <= 2) {
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

    // ==================== Layer 0: 按标记只保留最新一条 ====================

    /**
     * 对 {@link ContextPolicy#retainLatestOnlyMarkers()} 里的每个标记，找出文本以该标记开头的
     * 所有消息，只保留列表里最靠后的一条，更早的整条从消息列表里删掉（issue #37）。
     *
     * <p>和 micro_compact 的区别：micro_compact 压缩的是"内容太大"，结构（消息条数、顺序）不变，
     * 换成占位符后模型仍然知道"这里曾经有一次工具调用"；这里删的是"内容已过期"——旧的一条
     * 一旦被新的一条取代，就连"曾经存在过"这件事都不需要模型知道，所以是真删除而不是占位替换。
     *
     * <p>目前唯一的调用方是 DeepResearch 的批判反馈（{@code DeepResearchService
     * .CRITIQUE_FEEDBACK_MARKER}）：历史批判意见一旦被更新的一条取代就不再有参考价值，
     * 留着只会让后续轮次误以为"还要处理上一条批判提的问题"。这里刻意做成policy 驱动的通用能力
     * 而不是 DeepResearch 专属分支——{@link ContextCompactor} 本来就是给任何持有
     * {@code List<Message>} 历史的调用方复用的，不应该在这个共享类里认识 DeepResearch 的概念。
     */
    private void dropSupersededMarkedMessages(List<Message> messages) {
        for (String marker : policy.retainLatestOnlyMarkers()) {
            List<Integer> indices = indicesOf(messages, message -> matchesMarker(message, marker));
            // 倒序删除，避免前面的删除操作把后面待删索引位置"挤动"
            for (int i = indices.size() - 2; i >= 0; i--) {
                messages.remove((int) indices.get(i));
            }
        }
    }

    private static boolean matchesMarker(Message message, String marker) {
        String text = message.getText();
        return text != null && text.startsWith(marker);
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
     *
     * <p>要保留的是开头**连续的全部** SystemMessage，而不只是第一条：调用方在历史最前面拼的是
     * 一串独立的系统区块（日期、用户分层记忆、会话文件清单，见 {@code AgentLoopExecutor#stream}），
     * 它们性质相同、只是刻意拆成了几条以便各自开关。只认 {@code messages.get(0)} 会把后面那几条
     * 当成"历史"折进摘要——最直接的后果是模型看不到会话文件清单，于是不知道有哪些 fileId 能传给
     * {@code load_file_content}，用户记忆也一并丢掉。往最前面再插一条（R22 的模式级系统提示词）
     * 同样落在这个连续段里，不需要再改这里。
     */
    private void autoCompact(List<Message> messages, String currentQuestion) {
        int historyStart = 0;
        while (historyStart < messages.size() && messages.get(historyStart) instanceof SystemMessage) {
            historyStart++;
        }
        List<Message> systemPrompts = new ArrayList<>(messages.subList(0, historyStart));
        List<Message> history = new ArrayList<>(messages.subList(historyStart, messages.size()));

        String summary = summarise(history, currentQuestion);

        messages.clear();
        messages.addAll(systemPrompts);
        messages.add(new UserMessage("[对话已压缩] 以下是之前对话的摘要：\n" + summary));
        log.info("已将 {} 条历史消息压缩为一段摘要，保留了开头 {} 条系统消息", history.size(), systemPrompts.size());
    }

    /** 摘要本身要靠一次 LLM 调用，它同样可能失败——失败时降级为保留最近若干条，不能让整轮对话崩掉。 */
    private String summarise(List<Message> history, String currentQuestion) {
        try {
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(SUMMARY_SYSTEM_PROMPT),
                    new UserMessage(buildSummaryRequest(history, currentQuestion))));
            String summary = SynchronousLlmCall.call(chatModel, prompt).getResult().getOutput().getText();
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
