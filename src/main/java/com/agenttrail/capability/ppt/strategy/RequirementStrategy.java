package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.StructuredLlmCall;
import com.agenttrail.runtime.api.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptRequirement;
import com.agenttrail.capability.ppt.PptState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REQUIREMENT 状态（issue #24）：一次结构化输出的 LLM 调用，把用户原始需求提炼成
 * {@link PptRequirement}。不挂任何工具——复用 issue #18 的
 * {@link com.agenttrail.runtime.api.OutputType} 机制，和 {@code DeepResearchService} 生成
 * {@code ResearchPlan} 是同一套用法。
 */
public class RequirementStrategy implements PptGenerationStrategy {

    private static final Logger log = LoggerFactory.getLogger(RequirementStrategy.class);

    private final AgentLoopExecutor executor;

    public RequirementStrategy(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptState handledState() {
        return PptState.REQUIREMENT;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", java.util.Map.of(),
                OutputType.of(PptRequirement.class));
        String rawJson = executor.call(PptPrompts.REQUIREMENT + context.userRequirement(), params);
        try {
            PptRequirement requirement = StructuredLlmCall.parse(rawJson, PptRequirement.class);
            if (!requirement.hasValidTopic()) {
                log.info("PPT requirement collected conversationId={} topicPresent=false defaultsApplied=[] waitingInput=true",
                        context.conversationId());
                return context.withRequirement(null)
                        .withClarifyingQuestion("这份 PPT 主要想讲什么主题？");
            }
            java.util.List<String> defaults = new java.util.ArrayList<>();
            if (requirement.title() == null || requirement.title().isBlank()) defaults.add("title");
            if (requirement.audience() == null || requirement.audience().isBlank()) defaults.add("audience");
            if (requirement.slideCount() <= 0) defaults.add("slideCount");
            if (requirement.tone() == null || requirement.tone().isBlank()) defaults.add("tone");
            PptRequirement normalized = requirement.normalized();
            log.info("PPT requirement collected conversationId={} topicPresent=true defaultsApplied={} slideCount={} waitingInput=false",
                    context.conversationId(), defaults, normalized.slideCount());
            return context.withRequirement(normalized).withClarifyingQuestion(null);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "REQUIREMENT 状态解析失败，模型输出不是合法的 PptRequirement JSON: " + rawJson, malformed);
        }
    }
}
