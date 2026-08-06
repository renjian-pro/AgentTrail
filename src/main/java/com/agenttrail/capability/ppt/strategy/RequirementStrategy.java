package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptRequirement;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * REQUIREMENT 状态（issue #24）：一次结构化输出的 LLM 调用，把用户原始需求提炼成
 * {@link PptRequirement}。不挂任何工具——复用 issue #18 的
 * {@link com.agenttrail.loop.model.OutputType} 机制，和 {@code DeepResearchService} 生成
 * {@code ResearchPlan} 是同一套用法。
 */
public class RequirementStrategy implements PptGenerationStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
            PptRequirement requirement = MAPPER.readValue(JsonRepair.fixJson(rawJson), PptRequirement.class);
            return context.withRequirement(requirement);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "REQUIREMENT 状态解析失败，模型输出不是合法的 PptRequirement JSON: " + rawJson, malformed);
        }
    }
}
