package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CLARIFY 状态：一次不挂工具的 LLM 调用，只判断"用户这句话够不够开始做 PPT"，不产出任何 PPT 内容。
 *
 * <p><b>为什么必须是独立一个状态，而不是塞进 REQUIREMENT。</b>{@code ppt.requirement} 要求输出
 * 完整结构；若受众、页数或风格尚未确认，模型容易为了满足结构而补写默认值。完整性判定必须发生在
 * REQUIREMENT 之前，且必须是一次
 * 目标单一的调用——让同一次调用既"提炼需求"又"判断要不要提炼"，两个目标会互相污染。
 *
 * <p>模型判定前先拦截不包含任何主题的纯执行命令。模型可以判断自然语言是否清晰，但不能把
 * “生成吧”擅自扩写成一个虚构主题；否则固定标记会让编造出的默认需求直接穿透澄清阶段。
 * 其余输入由提示词按主题、页数、风格和受众四项检查，再采用固定标记优先、关键词兜底判定。
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

    private static final Set<String> BARE_GENERATION_COMMANDS = Set.of(
            "生成", "生成吧", "直接生成", "开始生成", "开始吧", "做吧",
            "生成ppt", "直接生成ppt", "开始生成ppt", "做个ppt", "做一份ppt",
            "帮我做个ppt", "帮我做一份ppt", "制作ppt", "来个ppt", "来一份ppt");

    private static final String BARE_COMMAND_QUESTION =
            "请补充 PPT 的主题、页数、风格和受众；给出这四项后我再开始生成。";

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
        if (isBareGenerationCommand(context.userRequirement())) {
            return context.withClarifyingQuestion(BARE_COMMAND_QUESTION);
        }
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

    private static boolean isBareGenerationCommand(String requirement) {
        if (requirement == null) {
            return true;
        }
        String normalized = requirement
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\\p{P}\\p{S}]+", "");
        return normalized.isEmpty() || BARE_GENERATION_COMMANDS.contains(normalized);
    }
}
