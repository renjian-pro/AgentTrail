package com.agenttrail.capability.ppt;

import java.nio.file.Path;

/** 产物上传请求；object key 由任务和内容摘要派生，重复 VERIFY 可复用同一对象。 */
public record PptArtifactUpload(String ownerId, long taskId, Path localFile, String contentType,
        String objectKey) {
    public PptArtifactUpload {
        if (ownerId == null || ownerId.isBlank() || taskId < 0 || localFile == null
                || contentType == null || contentType.isBlank() || objectKey == null || objectKey.isBlank()) {
            throw new IllegalArgumentException("PPT artifact upload request is incomplete");
        }
    }
}
