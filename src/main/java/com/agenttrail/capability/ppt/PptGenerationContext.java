package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * PPT 生成状态机贯穿全流程的上下文（issue #24）。不可变、"with" 语义——每个
 * {@link PptGenerationStrategy} 拿到当前上下文，做完自己那一段真实副作用之后，返回一个新的、
 * 多了一块数据的上下文，不是原地修改。
 *
 * <p>这也是"按状态粒度做 checkpoint"的具体载体（踩坑点 #44）：某个状态负责产出的字段，
 * 在这个状态真正跑完之前一直是 {@code null}；持久化的是这份上下文本身（见
 * {@link PptTaskStore}）。这些字段可以帮助诊断产物是否已经生成，但真正的恢复位置以
 * {@link PptTask#status()} 和其 revision 为准，避免把业务数据是否为空误当成状态机控制信号。
 *
 * <p>{@code clarifyingQuestion} 和 {@code warnings} 是不代表进度的附加信息：前者是 CLARIFY
 * 状态判定"信息不足"时写下的追问原文，后者记录成功但降级的用户提示。任务据此停在
 * {@link PptState#AWAITING_INPUT} 等用户回答时，追问原文**不被清空**——追问和回答一起被拼进
 * {@code userRequirement}（见 {@code PptGenerationService#answerClarification}），留着这一份原文
 * 是为了让"当初问了什么"在任务记录里可追溯；判断"是不是在等人"看的是状态而不是这两个字段。
 */
public record PptGenerationContext(
        String conversationId,
        String userRequirement,
        PptRequirement requirement,
        List<String> searchMaterials,
        String templatePath,
        PptOutline outline,
        PptSchema schema,
        String outputPath,
        String clarifyingQuestion,
        Integer contextVersion,
        List<PptWarning> warnings) {

    /** 当前快照版本；缺失该字段的旧 JSON 按这个版本读取并在下一次写回时补齐。 */
    public static final int CURRENT_CONTEXT_VERSION = 1;

    /** 旧快照未携带版本字段时，读取语义仍按当前兼容版本处理。 */
    @Override
    public Integer contextVersion() {
        return contextVersion == null ? CURRENT_CONTEXT_VERSION : contextVersion;
    }

    /** 旧快照没有 warnings 时按空列表读取，避免恢复后出现 null 分支。 */
    @Override
    public List<PptWarning> warnings() {
        return warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 兼容旧快照的反序列化入口：{@code clarifyingQuestion} 是后加的字段，历史 JSON 里没有这个键。
     * Jackson 对 record 的缺失字段会填 {@code null}，语义上正好是"这条老任务没经过澄清"，
     * 不需要为老数据做迁移。这个构造函数本身是给还在用 8 参数形式的调用方/测试留的。
     */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, null, CURRENT_CONTEXT_VERSION, List.of());
    }

    /** 兼容已经包含澄清字段但还没有上下文版本的中间快照。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, CURRENT_CONTEXT_VERSION, List.of());
    }

    /** 兼容已带上下文版本但还没有 warning 列表的调用方。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, List.of());
    }

    public static PptGenerationContext initial(String conversationId, String userRequirement) {
        return new PptGenerationContext(conversationId, userRequirement, null, null, null, null, null, null,
                null, CURRENT_CONTEXT_VERSION, List.of());
    }

    public PptGenerationContext withRequirement(PptRequirement requirement) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    public PptGenerationContext withSearchMaterials(List<String> searchMaterials) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    public PptGenerationContext withTemplatePath(String templatePath) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    public PptGenerationContext withOutline(PptOutline outline) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    public PptGenerationContext withSchema(PptSchema schema) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    /** CLARIFY 判定信息不足时写入追问原文；{@code null} 表示无需澄清，直接推进。 */
    public PptGenerationContext withClarifyingQuestion(String clarifyingQuestion) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    /** 用户答完澄清后重写需求原文（追问/回答的拼接在 {@code PptGenerationService} 里完成）。 */
    public PptGenerationContext withUserRequirement(String userRequirement) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    public PptGenerationContext withOutputPath(String outputPath) {
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), warnings());
    }

    /** 记录降级但仍可交付的结果，供任务查询和前端区别于 FAILED 的视觉提示使用。 */
    public PptGenerationContext withWarning(PptWarning warning) {
        List<PptWarning> next = new java.util.ArrayList<>(warnings());
        next.add(warning);
        return new PptGenerationContext(conversationId, userRequirement, requirement, searchMaterials, templatePath,
                outline, schema, outputPath, clarifyingQuestion, contextVersion(), next);
    }
}
