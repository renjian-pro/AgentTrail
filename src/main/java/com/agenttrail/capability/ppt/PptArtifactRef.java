package com.agenttrail.capability.ppt;

/** 上下文中长期保存的产物引用；不包含签名下载 URL。 */
public record PptArtifactRef(String artifactId, String objectKey, String checksum, long sizeBytes,
        String contentType) {
    public PptArtifactRef {
        if (artifactId == null || artifactId.isBlank() || objectKey == null || objectKey.isBlank()
                || checksum == null || checksum.isBlank() || sizeBytes < 0
                || contentType == null || contentType.isBlank()) {
            throw new IllegalArgumentException("PPT artifact reference is incomplete");
        }
    }

    public static PptArtifactRef from(PptArtifact artifact) {
        return new PptArtifactRef(artifact.artifactId(), artifact.objectKey(), artifact.checksum(),
                artifact.sizeBytes(), artifact.contentType());
    }
}
