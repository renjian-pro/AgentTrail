package com.agenttrail.loop.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.ppt.PptGenerationContext;
import com.agenttrail.loop.ppt.PptGenerationException;
import com.agenttrail.loop.ppt.PptGenerationStrategy;
import com.agenttrail.loop.ppt.PptOutline;
import com.agenttrail.loop.ppt.PptPrompts;
import com.agenttrail.loop.ppt.PptState;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * OUTLINE 状态（issue #24）：基于 REQUIREMENT + SEARCH 两个状态的产出，生成内容大纲
 * （{@link PptOutline}）——只规划"讲什么"，不知道最终填进哪个模板 shape，那是 SCHEMA 状态的事。
 */
public class OutlineStrategy implements PptGenerationStrategy {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentLoopExecutor executor;

    public OutlineStrategy(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptState handledState() {
        return PptState.OUTLINE;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", java.util.Map.of(),
                OutputType.of(PptOutline.class));
        String prompt = PptPrompts.OUTLINE.formatted(renderInput(context));
        String rawJson = executor.call(prompt, params);
        try {
            PptOutline outline = parseOutline(rawJson);
            return context.withOutline(outline);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "OUTLINE 状态解析失败，模型输出不是合法的 PptOutline JSON: " + rawJson, malformed);
        }
    }

    private static PptOutline parseOutline(String rawJson) throws java.io.IOException {
        JsonNode node = MAPPER.readTree(JsonRepair.fixJson(rawJson));
        if (node.has("content") && !node.has("deckTitle") && node.get("content").isTextual()) {
            node = MAPPER.readTree(JsonRepair.fixJson(node.get("content").asText()));
        }
        return MAPPER.treeToValue(node, PptOutline.class);
    }

    private static String renderInput(PptGenerationContext context) {
        StringBuilder builder = new StringBuilder();
        var requirement = context.requirement();
        builder.append("【制作需求】\n")
                .append("标题：").append(requirement.title()).append('\n')
                .append("主题：").append(requirement.topic()).append('\n')
                .append("受众：").append(requirement.audience()).append('\n')
                .append("建议内容页数：").append(requirement.slideCount()).append('\n')
                .append("语言风格：").append(requirement.tone()).append("\n\n")
                .append("【检索素材】\n");
        var searchMaterials = context.searchMaterials() == null
                ? java.util.List.<String>of()
                : context.searchMaterials();
        for (String material : searchMaterials) {
            builder.append("- ").append(material).append('\n');
        }
        return builder.toString();
    }
}
