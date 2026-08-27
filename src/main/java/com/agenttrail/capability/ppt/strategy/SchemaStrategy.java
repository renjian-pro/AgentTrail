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
import com.agenttrail.capability.ppt.PptTemplateRef;
import com.agenttrail.capability.ppt.PptTemplateRegistry;
import com.agenttrail.capability.ppt.PptTemplateVersion;
import com.agenttrail.capability.ppt.PptWarning;

import java.util.Comparator;
import java.util.List;

/**
 * SCHEMA 状态（issue #24）：把 {@link com.agenttrail.capability.ppt.PptOutline} 翻译成"填进这份具体
 * 模板"的最终文字（{@link PptSchema}）——字数上限写进 Prompt 只是软约束（踩坑点 #53），
 * 真正的硬性截断兜底在 RENDER 状态调用的 {@code render_ppt.py} 里，这里不重复做截断。
 *
 * <p>{@code context.userRequirement()} 也会拼进 Prompt（issue #32）：CREATE 流程里这个字段是
 * 触发本次生成的原始需求文本，和大纲信息重复，附带上不会有副作用；MODIFY 流程里
 * （{@link com.agenttrail.capability.ppt.PptGenerationService}）这个字段被替换成用户这次的具体修改
 * 指令（例如"把第二页标题改成……"），SCHEMA 状态是 MODIFY 分支唯一重新执行的 LLM 调用，
 * 只有让它看到修改指令，“在已有 PPT 基础上改”才是真的在改，而不是重复输出旧大纲。
 */
public class SchemaStrategy implements PptGenerationStrategy {

    private final AgentLoopExecutor executor;
    private final PptTemplateRegistry templateRegistry;

    public SchemaStrategy(AgentLoopExecutor executor) {
        this(executor, null);
    }

    public SchemaStrategy(AgentLoopExecutor executor, PptTemplateRegistry templateRegistry) {
        this.executor = executor;
        this.templateRegistry = templateRegistry;
    }

    @Override
    public PptState handledState() {
        return PptState.SCHEMA;
    }

    @Override
    public PptGenerationContext execute(PptGenerationContext context) {
        PptTemplateVersion template = resolvePinnedTemplate(context);
        RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", java.util.Map.of(),
                OutputType.of(PptSchema.class));
        String prompt = PptPrompts.SCHEMA + renderTemplateContract(template) + renderOutline(context);
        String rawJson = executor.call(prompt, params);
        PptSchema schema;
        try {
            schema = StructuredLlmCall.parse(rawJson, PptSchema.class);
        } catch (Exception malformed) {
            throw new PptGenerationException(
                    "SCHEMA 状态解析失败，模型输出不是合法的 PptSchema JSON: " + rawJson, malformed);
        }
        try {
            if (template == null) {
                PptSchemaValidator.validate(schema);
            } else {
                PptSchemaValidator.validate(schema, template);
            }
            return context.withSchema(schema);
        } catch (PptGenerationException contractMismatch) {
            PptSchema legacyFallback = legacyFallback(schema, template);
            if (legacyFallback == null) {
                throw new PptGenerationException("SCHEMA 状态模板契约校验失败: "
                        + contractMismatch.getMessage(), contractMismatch);
            }
            List<String> affectedPageIds = schema.pages().stream().map(page -> page.pageId()).toList();
            return context.withSchema(legacyFallback).withWarning(new PptWarning(
                    "PPT_DYNAMIC_SCHEMA_DEGRADED", PptState.SCHEMA,
                    "动态页面与选定模板不匹配，已使用兼容版式继续生成", affectedPageIds));
        }
    }

    /**
     * 模型偶尔会按内容语义自创 COMPARE/TABLE，即使固定模板只支持 CONTENT/TEXT。此时兼容字段
     * 已经是同一轮生成的完整内容，验证通过后可安全走旧渲染协议；没有有效兼容字段则保留原失败。
     */
    private static PptSchema legacyFallback(PptSchema schema, PptTemplateVersion template) {
        if (template == null || schema.pages().isEmpty()) {
            return null;
        }
        PptSchema fallback = new PptSchema(schema.titleText(), schema.subtitleText(), schema.contentSlides(),
                schema.coverImageUrl(), List.of(), template.templateId(), template.version());
        try {
            PptSchemaValidator.validate(fallback);
            return fallback;
        } catch (PptGenerationException invalidLegacyFields) {
            return null;
        }
    }

    private PptTemplateVersion resolvePinnedTemplate(PptGenerationContext context) {
        PptTemplateRef ref = context.templateRef();
        if (ref == null || templateRegistry == null) {
            return null;
        }
        PptTemplateVersion template = templateRegistry.validate(ref.templateId(), ref.version());
        if (!ref.artifactId().equals(template.artifactId()) || !ref.checksum().equals(template.checksum())) {
            throw new PptGenerationException("PPT 任务固定的模板版本与注册表不一致: "
                    + ref.templateId() + "/" + ref.version());
        }
        return template;
    }

    /**
     * 把模板字段契约直接交给模型。通用 JSON Schema 生成器只能识别 fields 是 object，无法表达
     * Map value 必须是 PptField；这里给出真实字段名和业务约束，避免模型把文本简写成字符串。
     */
    private static String renderTemplateContract(PptTemplateVersion template) {
        if (template == null) {
            return "\n## 选定模板契约\n当前任务没有注册模板契约；仅生成兼容字段，并将 pages 设为空数组。\n\n";
        }
        StringBuilder builder = new StringBuilder("\n## 选定模板契约\n")
                .append("模板 ID：").append(template.templateId()).append('\n')
                .append("模板版本：").append(template.version()).append('\n')
                .append("逐页字段映射：").append(template.description()).append('\n')
                .append("支持的 pageType：");
        template.supportedPageTypes().stream().sorted(Comparator.comparing(Enum::name))
                .forEach(type -> builder.append(type.name()).append(' '));
        builder.append("\n字段定义：\n");
        template.templateSchema().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> builder.append("- ").append(entry.getKey())
                        .append("：type=").append(entry.getValue().type())
                        .append("，required=").append(entry.getValue().required())
                        .append("，maxChars=").append(entry.getValue().maxChars()).append('\n'));
        return builder.append('\n').toString();
    }

    private static String renderOutline(PptGenerationContext context) {
        var outline = context.outline();
        StringBuilder builder = new StringBuilder("## PPT 大纲\n");
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
