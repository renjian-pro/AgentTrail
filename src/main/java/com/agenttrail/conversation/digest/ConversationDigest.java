package com.agenttrail.conversation.digest;

import com.agenttrail.web.dto.ConversationTurnResponse;

import java.util.List;
import java.util.Optional;

/**
 * 会话上下文摘要（issue #103）。
 *
 * <p><b>解决什么</b>：用户说"根据前面的会话帮我生成 PPT"，现在会拿到一份**题为"前面的会话"的
 * 幻灯片**，而且不报错——{@code PptGenerationContext.initial(conversationId, userMessage)} 只收到
 * 那一句话，{@code conversationId} 仅作分组键，前面聊了什么一个字都不传。DeepResearch 同理。
 *
 * <p><b>为什么传摘要而不是消息历史</b>：消息历史里带 {@code tool_calls} 结构，指向的工具在任务
 * 链路里根本不存在；跨到工具集不同的一侧就是无效的调用记录。**摘要是数据，历史是可执行的调用
 * 记录**——这个区别让"任务不影响会话的工具集"和"任务能读到上下文"同时成立：任务消费的是会话的
 * 内容，不是会话的执行状态（requirements §7.4）。
 *
 * <p><b>触发按会话状态判定，不按语义</b>：在已有会话里发起任务就带摘要，新会话里发起就不带。
 * 不去判断用户有没有说"根据前面的"——沿用 {@code PptIntentRecognizer} 和 DeepResearch 需求澄清
 * 已经定案的那条（踩坑点 #52）：判定成本低、行为可预测、说得清依据。
 */
public final class ConversationDigest {

    /** 超过这个长度就交给模型压一压；以内直接用原文，省一次 LLM 调用也少一层信息损失。 */
    static final int COMPRESSION_THRESHOLD_CHARS = 2000;
    /** 只取最近这些轮——更早的内容对"接着刚才那个话题做点什么"几乎没有贡献，白占预算。 */
    static final int MAX_TURNS = 8;

    private ConversationDigest() {
    }

    /**
     * 把会话历史渲染成纯文本。空会话返回 empty，调用方据此决定带不带——**返回空串会让下游
     * 分不清"没有上下文"和"上下文是空的"**，前者应该原样发起任务，后者是数据异常。
     */
    public static Optional<String> render(List<ConversationTurnResponse> turns) {
        if (turns == null || turns.isEmpty()) {
            return Optional.empty();
        }
        List<ConversationTurnResponse> recent = turns.size() <= MAX_TURNS
                ? turns : turns.subList(turns.size() - MAX_TURNS, turns.size());
        StringBuilder text = new StringBuilder();
        for (ConversationTurnResponse turn : recent) {
            if (turn.question() != null && !turn.question().isBlank()) {
                text.append("用户：").append(turn.question().strip()).append('\n');
            }
            if (turn.answer() != null && !turn.answer().isBlank()) {
                text.append("助手：").append(turn.answer().strip()).append('\n');
            }
        }
        String rendered = text.toString().strip();
        return rendered.isEmpty() ? Optional.empty() : Optional.of(rendered);
    }

    public static boolean needsCompression(String digest) {
        return digest != null && digest.length() > COMPRESSION_THRESHOLD_CHARS;
    }

    /**
     * 拼进任务入参的形式。标题写明这是"之前对话的摘要"而不是用户这次的要求——不加这层标注，
     * 模型会把摘要里的历史提问当成本次任务的指令。
     */
    public static String asTaskPrefix(String digest, String userMessage) {
        return """
                # 之前对话的摘要（供参考，不是本次要求）

                %s

                # 本次要求

                %s""".formatted(digest.strip(), userMessage.strip());
    }
}
