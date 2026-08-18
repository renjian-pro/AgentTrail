package com.agenttrail.capability.ppt;

import java.time.Duration;
import java.util.Optional;

/** 产物对象存储端口；数据库只保存 PptArtifact 的稳定元数据。 */
public interface PptArtifactStore {
    PptArtifact put(PptArtifactUpload upload);

    Optional<PptArtifact> find(String artifactId);

    String signedDownloadUrl(String artifactId, Duration validity);
}
