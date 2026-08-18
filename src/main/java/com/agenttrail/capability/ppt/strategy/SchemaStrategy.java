package com.agenttrail.capability.ppt.strategy;

import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.core.StructuredLlmCall;
import com.agenttrail.runtime.api.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.capability.ppt.PptGenerationContext;
import com.agenttrail.capability.ppt.PptGenerationException;
import com.agenttrail.capability.ppt.PptGenerationStrategy;
import com.agenttrail.capability.ppt.PptOutlineSlide;
import com.agenttrail.capability.ppt.PptPrompts;
import com.agenttrail.capability.ppt.PptSchema;
import com.agenttrail.capability.ppt.PptState;
import com.agenttrail.capability.ppt.PptSchemaValidator;

/**
 * SCHEMA 状态（issue #24）：把 {@link com.agenttrail.capability.ppt.PptOutline} 翻译成"填进这份具体
 * 模板"的最终文字（{@link PptSchema}）——字数上限写进 Prompt 只是软约束（踩坑点 #53），
 * 真正的硬性截断兜底在 RENDER 状态调用的 {@code render_ppt.py} 里，这里不重复做截断。
 *
 * <p>{@code context.userRequirement()} 也会拼进 Prompt（issue #32）：CREATE 流程里这个字段是
 * 触发本次生成的原始需求文本，和大纲信息重复，附带上不会有副作用；MODIFY 流程里
 * （{@link com.agenttrail.capability.ppt.PptGenerationService}）这个字段被替换成用户这次的具体修改
 * 指令（例如"把第二页标题改成……"），SCHEMA 状态是 MODIFY 分支唯一重新执行的 LLM 调用，
 * 只有让它看到修改指令，"在已有 PPT 基础上改"才是真的在改，而不是照抄一遍旧大纲。
 */
public class SchemaStrategy implements PptGenerationStrategy {

    private final AgentLoopExecutor executor;

    public SchemaStrategy(AgentLoopExecutor executor) {
        this.executor = executor;
    }

    @Override
    public PptState handledState() {
        return PptState.SCHEMA;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", java.util.Map.of(),
                OutputType.of(PptSchema.class));
        String prompt = PptPrompts.SCHEMA + renderOutline(context);
        String rawJson = executor.call(prompt, params);
        try {
            PptSchema schema = StructuredLlmCall.parse(rawJson, PptSchema.class);
            PptSchemaValidator.validate(schema);
            return context.withSchema(schema);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "SCHEMA 状态解析失败，模型输出不是合法的 PptSchema JSON: " + rawJson, malformed);
        }
    }

    private static String renderOutline(PptGenerationContext context) {
        var outline = context.outline();
        StringBuilder builder = new StringBuilder();
        builder.append("封面主标题：").append(outline.deckTitle()).append('\n')
                .append("封面副标题：").append(outline.deckSubtitle()).append("\n\n")
                .append("内容页（按顺序）：\n");
        int index = 1;
        for (PptOutlineSlide slide : outline.slides()) {
            builder.append(index++).append(". ").append(slide.title()).append('\n');
            for (String bullet : slide.bullets()) {
                builder.append("   - ").append(bullet).append('\n');
            }
        }
        String userRequirement = context.userRequirement();
        if (userRequirement != null && !userRequirement.isBlank()) {
            builder.append("\n【用户原始请求，如有具体的修改/调整要求以此为准】\n").append(userRequirement).append('\n');
        }
        return builder.toString();
    }
}
