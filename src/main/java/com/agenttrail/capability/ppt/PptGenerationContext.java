package com.agenttrail.capability.ppt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** PPT 状态机的不可变 checkpoint；Schema 是页面内容和图片地址的唯一事实源。 */
@JsonIgnoreProperties(ignoreUnknown = true)
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
        PptTemplateRef templateRef,
        PptArtifactRef artifactRef,
        String operation,
        Long baseTaskId,
        String baseArtifactId) {

    public static final int CURRENT_CONTEXT_VERSION = 1;

    /** 兼容没有修改操作元数据的旧调用方。 */
    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion,
            List<PptWarning> warnings, PptVisualPlan visualPlan,
            PptTemplateRef templateRef, PptArtifactRef artifactRef) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, warnings, visualPlan,
                templateRef, artifactRef, "CREATE", null, null);
    }

    @Override
    public Integer contextVersion() {
        return contextVersion == null ? CURRENT_CONTEXT_VERSION : contextVersion;
    }

    @Override
    public List<PptWarning> warnings() {
        return warnings == null ? List.of() : List.copyOf(warnings);
    }

    @Override
    public String operation() {
        return operation == null || operation.isBlank() ? "CREATE" : operation;
    }

    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, null, CURRENT_CONTEXT_VERSION, List.of(), null, null, null);
    }

    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, CURRENT_CONTEXT_VERSION, List.of(), null, null, null);
    }

    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, List.of(), null, null, null);
    }

    public PptGenerationContext(String conversationId, String userRequirement, PptRequirement requirement,
            List<String> searchMaterials, String templatePath, PptOutline outline, PptSchema schema,
            String outputPath, String clarifyingQuestion, Integer contextVersion, List<PptWarning> warnings) {
        this(conversationId, userRequirement, requirement, searchMaterials, templatePath, outline, schema,
                outputPath, clarifyingQuestion, contextVersion, warnings, null, null, null);
    }

    public static PptGenerationContext initial(String conversationId, String userRequirement) {
        return new PptGenerationContext(conversationId, userRequirement, null, null, null, null, null, null,
                null, CURRENT_CONTEXT_VERSION, List.of(), null, null, null);
    }

    private PptGenerationContext copy(List<String> nextSearchMaterials, String nextTemplatePath,
            PptOutline nextOutline, PptSchema nextSchema, String nextOutputPath,
            String nextClarifyingQuestion, List<PptWarning> nextWarnings,
            PptVisualPlan nextVisualPlan, PptTemplateRef nextTemplateRef,
            PptArtifactRef nextArtifactRef, PptRequirement nextRequirement, String nextUserRequirement) {
        return copyWithMetadata(nextSearchMaterials, nextTemplatePath, nextOutline, nextSchema, nextOutputPath,
                nextClarifyingQuestion, nextWarnings, nextVisualPlan, nextTemplateRef,
                nextArtifactRef, nextRequirement, nextUserRequirement, operation(), baseTaskId, baseArtifactId);
    }

    private PptGenerationContext copyWithMetadata(List<String> nextSearchMaterials, String nextTemplatePath,
            PptOutline nextOutline, PptSchema nextSchema, String nextOutputPath,
            String nextClarifyingQuestion, List<PptWarning> nextWarnings,
            PptVisualPlan nextVisualPlan, PptTemplateRef nextTemplateRef,
            PptArtifactRef nextArtifactRef, PptRequirement nextRequirement, String nextUserRequirement,
            String nextOperation, Long nextBaseTaskId, String nextBaseArtifactId) {
        return new PptGenerationContext(conversationId, nextUserRequirement, nextRequirement,
                nextSearchMaterials, nextTemplatePath, nextOutline, nextSchema, nextOutputPath,
                nextClarifyingQuestion, contextVersion(), nextWarnings, nextVisualPlan,
                nextTemplateRef, nextArtifactRef, nextOperation, nextBaseTaskId, nextBaseArtifactId);
    }

    public PptGenerationContext withRequirement(PptRequirement requirement) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withSearchMaterials(List<String> searchMaterials) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withTemplatePath(String templatePath) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withOutline(PptOutline outline) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withSchema(PptSchema schema) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withClarifyingQuestion(String clarifyingQuestion) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withUserRequirement(String userRequirement) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withOutputPath(String outputPath) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withWarning(PptWarning warning) {
        List<PptWarning> next = new java.util.ArrayList<>(warnings());
        next.add(warning);
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                next, visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withVisualPlan(PptVisualPlan visualPlan) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withTemplateRef(PptTemplateRef templateRef) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withArtifactRef(PptArtifactRef artifactRef) {
        return copy(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement);
    }

    public PptGenerationContext withOperationMetadata(String operation, Long baseTaskId, String baseArtifactId) {
        return copyWithMetadata(searchMaterials, templatePath, outline, schema, outputPath, clarifyingQuestion,
                warnings(), visualPlan, templateRef, artifactRef, requirement, userRequirement,
                operation, baseTaskId, baseArtifactId);
    }
}
