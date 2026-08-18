package com.agenttrail.capability.ppt;

/**
 * Schema 扫描出的逐页素材任务。封面图和内容页图片使用同一模型，artifactId 写回后即可在
 * 重试、替换和清理流程中追踪版本关系。
 */
public record PptAssetTask(String pageId, String fieldName, PptFieldType type, String prompt,
        PptVisualPlan visualPlan, PptAssetStatus status, String artifactId,
        PptAssetProvenance provenance, int attempt, String contentDigest, String replacedArtifactId) {
    public PptAssetTask {
        if (pageId == null || pageId.isBlank() || fieldName == null || fieldName.isBlank()
                || type == null || prompt == null || prompt.isBlank() || status == null
                || contentDigest == null || contentDigest.isBlank() || attempt < 0) {
            throw new IllegalArgumentException("PPT asset task identity and prompt are required");
        }
    }

    public static PptAssetTask planned(String pageId, String fieldName, PptFieldType type,
            String prompt, PptVisualPlan visualPlan, String contentDigest) {
        return new PptAssetTask(pageId, fieldName, type, prompt, visualPlan, PptAssetStatus.PLANNED,
                null, null, 0, contentDigest, null);
    }

    public PptAssetTask running() {
        return new PptAssetTask(pageId, fieldName, type, prompt, visualPlan, PptAssetStatus.RUNNING,
                artifactId, provenance, attempt + 1, contentDigest, replacedArtifactId);
    }

    public PptAssetTask succeeded(String artifactId, PptAssetProvenance provenance) {
        return new PptAssetTask(pageId, fieldName, type, prompt, visualPlan, PptAssetStatus.SUCCEEDED,
                artifactId, provenance, Math.max(attempt, 1), contentDigest, replacedArtifactId);
    }

    public PptAssetTask failed() {
        return new PptAssetTask(pageId, fieldName, type, prompt, visualPlan, PptAssetStatus.FAILED,
                artifactId, provenance, Math.max(attempt, 1), contentDigest, replacedArtifactId);
    }
}
