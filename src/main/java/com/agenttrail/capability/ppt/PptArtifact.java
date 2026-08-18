package com.agenttrail.capability.ppt;

/**
 * 渲染并 VERIFY 后的稳定产物元数据。任务上下文只保存 artifactRef，不保存签名 URL；下载时再临时签名。
 */
public record PptArtifact(String artifactId, String ownerId, long taskId, String objectKey,
        String checksum, long sizeBytes, String contentType, long createdAtMillis, String retentionStatus) {
    public PptArtifact {
        if (artifactId == null || artifactId.isBlank() || ownerId == null || ownerId.isBlank()
                || objectKey == null || objectKey.isBlank() || checksum == null || checksum.isBlank()
                || sizeBytes < 0 || contentType == null || contentType.isBlank()
                || retentionStatus == null || retentionStatus.isBlank()) {
            throw new IllegalArgumentException("PPT artifact metadata is incomplete");
        }
    }
}
