package com.agenttrail.capability.ppt.application;

import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptRequirement;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.StructuredLlmCall;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.runtime.api.OutputType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模型只负责提取字段；是否完整、如何追问和最终提交文本均由确定性代码决定。 */
public final class LlmPptRequirementPreflight implements PptRequirementPreflight {

    private final AgentLoopExecutor executor;

    public LlmPptRequirementPreflight(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptPreflightOutcome assess(String conversationId, String conversationContext) {
        RunnableParams params = new RunnableParams(UUID.randomUUID().toString(), "ppt-preflight", Map.of(),
                OutputType.of(PptRequirementDraft.class));
        String raw = executor.call(PptPrompts.PREFLIGHT + safe(conversationContext), params);
        final PptRequirementDraft draft;
        try {
            draft = StructuredLlmCall.parse(raw, PptRequirementDraft.class);
        } catch (RuntimeException malformed) {
            throw new PptGenerationException("PPT 需求预检解析失败", malformed);
        }

        List<String> missing = missingFields(draft);
        if (!missing.isEmpty()) {
            return new PptPreflightOutcome(false, renderQuestion(missing), null);
        }
        PptRequirement requirement = new PptRequirement(
                blank(draft.title()) ? draft.topic().strip() : draft.title().strip(),
                draft.topic().strip(), draft.audience().strip(), draft.slideCount(), draft.tone().strip());
        return new PptPreflightOutcome(true, renderConfirmation(requirement), renderGenerationRequest(requirement));
    }

    private static List<String> missingFields(PptRequirementDraft draft) {
        List<String> missing = new ArrayList<>();
        PptRequirement topicProbe = new PptRequirement(draft.title(), draft.topic(), draft.audience(),
                draft.slideCount(), draft.tone());
        if (!topicProbe.hasValidTopic()) missing.add("主题：这份 PPT 具体想讲什么？");
        if (draft.slideCount() <= 0 || draft.slideCount() > PptRequirement.MAX_SLIDE_COUNT) {
            missing.add("页数：期望的 PPT 总页数是多少？");
        }
        if (blank(draft.tone())) missing.add("风格：希望采用商务、科技、极简还是其它风格？");
        if (blank(draft.audience())) missing.add("受众：这份 PPT 主要给谁看？");
        return missing;
    }

    private static String renderQuestion(List<String> missing) {
        StringBuilder text = new StringBuilder("开始生成前，还需要确认以下信息：\n");
        for (int index = 0; index < missing.size(); index++) {
            text.append(index + 1).append(". ").append(missing.get(index)).append('\n');
        }
        return text.append("\n请直接在下方输入框补充；需求确认后才会创建 PPT 任务。").toString();
    }

    private static String renderConfirmation(PptRequirement requirement) {
        return """
                需求已确认，开始生成 PPT。
                - 主题：%s
                - 页数：%d 页
                - 风格：%s
                - 受众：%s""".formatted(requirement.topic(), requirement.slideCount(),
                requirement.tone(), requirement.audience());
    }

    private static String renderGenerationRequest(PptRequirement requirement) {
        return """
                请生成一份 PPT。
                标题：%s
                主题：%s
                页数：%d
                风格：%s
                受众：%s""".formatted(requirement.title(), requirement.topic(), requirement.slideCount(),
                requirement.tone(), requirement.audience());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
