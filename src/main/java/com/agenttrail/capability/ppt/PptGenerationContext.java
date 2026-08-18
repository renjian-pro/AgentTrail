package com.agenttrail.capability.ppt;

import java.util.List;

/**
 * PPT 生成状态机贯穿全流程的不可变上下文（issue #24）。每个 Strategy 完成自己的真实副作用后
 * 返回新的上下文；这份完整快照就是状态粒度 checkpoint 的业务数据载体。
 *
 * <p>真正的恢复位置仍由 {@link PptTask#status()} 和 revision 决定，字段是否为空只用于诊断。
 * {@code clarifyingQuestion}/{@code warnings} 是附加信息，{@code visualPlan} 和 assetTasks 是
 * 需要和模板、Schema、素材阶段共享并持久化的业务契约。
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
        List<PptWarning> warnings,
        PptVisualPlan visualPlan,
        List<PptAssetTask> assetTasks,
        PptTemplateRef templateRef,
        PptArtifactRef artifactRef) {

    /** 当前快照版本；旧 JSON 缺失版本时按此版本兼容读取。 */
    public static final int CURRENT_CONTEXT_VERSION = 1;

    @Override
    public Integer contextVersion() {
        return contextVersion == null ? CURRENT_CONTEXT_VERSION : contextVersion;
    }

    /** 旧快照没有 warnings 时按空列表读取，避免恢复后出现 null 分支。 */
    @Override
    public List<PptWarning> warnings() {
        return warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 旧快照未携带素材任务时按空列表读取；不会在恢复时临时猜测素材。 */
    @Override
    public List<PptAssetTask> assetTasks() {
        return assetTasks == null ? List.of() : List.copyOf(assetTasks);
    }

    /** 旧任务没有模板注册引用时保持 null；新任务由 TEMPLATE Strategy 固定版本。 */
    @Override
    public PptTemplateRef templateRef() {
        return templateRef;
    }

    /** VERIFY 成功并上传后写入稳定对象引用；本地临时 outputPath 仍只供当前渲染阶段使用。 */
    @Override
    public PptArtifactRef artifactRef() {
        return artifactRef;
    }

    /** 兼容 issue #24 时期的 8 参数上下文。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, null, CURRENT_CONTEXT_VERSION, List.of(), null, List.of(), null, null);
    }

    /** 兼容已包含澄清字段但还没有版本/warning 的中间快照。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, CURRENT_CONTEXT_VERSION, List.of(), null, List.of(), null, null);
    }

    /** 兼容已带上下文版本但还没有 warning/视觉规划/素材任务的调用方。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, List.of(), null, List.of(), null, null);
    }

    /** 兼容上一版已经持久化 warning 列表的上下文。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion, List<PptWarning> warnings) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, warnings, null, List.of(), null, null);
    }

    public static PptGenerationContext initial(String conversationId, String userRequirement) {
        return new PptGenerationContext(conversationId, userRequirement, null, null, null, null, null, null,
                null, CURRENT_CONTEXT_VERSION, List.of(), null, List.of(), null, null);
    }

    private PptGenerationContext copy(List<String> nextSearchMaterials, String nextTemplatePath,
            PptOutline nextOutline, PptSchema nextSchema, String nextOutputPath,
            String nextClarifyingQuestion, List<PptWarning> nextWarnings,
            PptVisualPlan nextVisualPlan, List<PptAssetTask> nextAssetTasks, PptTemplateRef nextTemplateRef,
            PptArtifactRef nextArtifactRef,
            PptRequirement nextRequirement, String nextUserRequirement) {
        return new PptGenerationContext(conversationId, nextUserRequirement, nextRequirement,
                nextSearchMaterials, nextTemplatePath, nextOutline, nextSchema, nextOutputPath,
                nextClarifyingQuestion, contextVersion(), nextWarnings, nextVisualPlan, nextAssetTasks,
                nextTemplateRef, nextArtifactRef);
    }

    public PptGenerationContext withRequirement(PptRequirement requirement) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withSearchMaterials(List<String> searchMaterials) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withTemplatePath(String templatePath) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withOutline(PptOutline outline) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withSchema(PptSchema schema) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** CLARIFY 判定信息不足时写入追问原文；判断是否等人看状态，不看此字段。 */
    public PptGenerationContext withClarifyingQuestion(String clarifyingQuestion) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** 用户答完澄清后重写需求原文，保留原追问以便审计。 */
    public PptGenerationContext withUserRequirement(String userRequirement) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withOutputPath(String outputPath) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** 记录降级但仍可交付的结果，供任务查询和前端区别于 FAILED 的视觉提示使用。 */
    public PptGenerationContext withWarning(PptWarning warning) {
        List<PptWarning> next = new java.util.ArrayList<>(warnings());
        next.add(warning);
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                next, visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** 写入全局视觉规划，模板/Schema/素材阶段只读取这一份规划。 */
    public PptGenerationContext withVisualPlan(PptVisualPlan visualPlan) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** 追加逐页素材任务，保持完整列表随 checkpoint 持久化。 */
    public PptGenerationContext withAssetTask(PptAssetTask assetTask) {
        List<PptAssetTask> next = new java.util.ArrayList<>(assetTasks());
        next.add(assetTask);
        return withAssetTasks(next);
    }

    public PptGenerationContext withAssetTasks(List<PptAssetTask> assetTasks) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks, templateRef, artifactRef, requirement, userRequirement);
    }

    /** 按 pageId/fieldName 替换素材任务，避免重试留下多个“当前”记录。 */
    public PptGenerationContext replaceAssetTask(PptAssetTask replacement) {
        List<PptAssetTask> next = new java.util.ArrayList<>(assetTasks());
        for (int i = 0; i < next.size(); i++) {
            PptAssetTask existing = next.get(i);
            if (existing.pageId().equals(replacement.pageId())
                    && existing.fieldName().equals(replacement.fieldName())) {
                next.set(i, replacement);
                return withAssetTasks(next);
            }
        }
        next.add(replacement);
        return withAssetTasks(next);
    }

    /** TEMPLATE Strategy 写入固定的 id/version/artifact/checksum/path 引用。 */
    public PptGenerationContext withTemplateRef(PptTemplateRef templateRef) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }

    /** VERIFY Strategy 上传产物后写入 stable artifact 引用。 */
    public PptGenerationContext withArtifactRef(PptArtifactRef artifactRef) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, assetTasks(), templateRef, artifactRef, requirement, userRequirement);
    }
}
