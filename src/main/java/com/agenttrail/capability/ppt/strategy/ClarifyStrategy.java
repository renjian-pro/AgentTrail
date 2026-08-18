package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptState;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * CLARIFY 状态：一次不挂工具的 LLM 调用，只判断"用户这句话够不够开始做 PPT"，不产出任何 PPT 内容。
 *
 * <p><b>为什么必须是独立一个状态，而不是塞进 REQUIREMENT。</b>{@code ppt.requirement} 提示词对每个
 * 缺失字段都写着"用户没有明确说明时合理推断一个"——那是刻意的，受众/页数/风格这些确实不该拦住用户。
 * 但它的代价是：需求含糊这件事在 REQUIREMENT 里会被默认值悄悄抹平，模型照样吐出一个编出来的
 * {@code title}/{@code topic}，然后一路喂给 SEARCH 去联网检索。判定必须发生在那之前，且必须是一次
 * 目标单一的调用——让同一次调用既"提炼需求"又"判断要不要提炼"，两个目标会互相污染。
 *
 * <p>判定结构和 {@code DeepResearchService#needsMoreInfo} 完全同构（踩坑点 #52 的既有结论）：
 * 固定标记优先、关键词兜底、两者都不命中时默认视为信息充分——宁可少追问也不要反复打断，
 * 这条倾向性和澄清提示词正文里写的"能推断出方向就直接开始"是同一个取舍的两处表达。
 *
 * <p>本状态**不负责**"停下来等人"这件事：它只是把追问原文写进上下文
 * （{@link PptGenerationContext#withClarifyingQuestion}），由 {@code PptGenerationService#run} 看到
 * 非空的追问后把任务落到 {@link PptState#AWAITING_INPUT}。Strategy 只描述"这个状态产出了什么"，
 * 状态怎么走始终是状态机的职责——把暂停逻辑写进 Strategy 会让它变成第二个编排者。
 */
public class ClarifyStrategy implements PptGenerationStrategy {

    /** 没有命中固定标记时的关键词兜底——同样不做语义解析，只是简单的包含匹配。 */
    private static final List<String> INSUFFICIENT_INFO_KEYWORDS = List.of(
            "请提供更多", "能否说明", "需要您提供", "请补充", "不够明确", "请问您");

    private final AgentLoopExecutor executor;

    public ClarifyStrategy(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptState handledState() {
        return PptState.CLARIFY;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        String verdict = executor.call(PptPrompts.CLARIFICATION + context.userRequirement(), freshParams());
        if (!needsMoreInfo(verdict)) {
            return context;
        }
        return context.withClarifyingQuestion(stripMarkers(verdict));
    }

    /**
     * 判定是一次和会话历史无关的独立调用，用随机会话 id 而不是 {@code context.conversationId()}
     * ——和 {@code SearchStrategy#freshParams}/{@code DeepResearchService#freshParams} 同一个理由：
     * 这次调用只看这一句需求本身，把会话历史卷进来只会让判定被之前的对话带偏。
     */
    private static RunnableParams freshParams() {
        return new RunnableParams(UUID.randomUUID().toString(), "ppt-generation", Map.of(), null);
    }

    /** 固定标记优先；两个标记都没出现时才走关键词兜底，都不命中默认视为信息充分。 */
    private static boolean needsMoreInfo(String modelResponse) {
        if (modelResponse == null) {
            return false;
        }
        if (modelResponse.contains(PptPrompts.NEEDS_INFO_MARKER)) {
            return true;
        }
        if (modelResponse.contains(PptPrompts.READY_MARKER)) {
            return false;
        }
        return INSUFFICIENT_INFO_KEYWORDS.stream().anyMatch(modelResponse::contains);
    }

    private static String stripMarkers(String modelResponse) {
        return modelResponse.replace(PptPrompts.NEEDS_INFO_MARKER, "").trim();
    }
}
