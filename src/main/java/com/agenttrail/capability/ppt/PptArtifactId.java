package com.agenttrail.capability.ppt;

import java.nio.file.Path;

/** 产物 ID/key 的稳定派生工具。 */
public final class PptArtifactId {
    private PptArtifactId() {
    }

    public static String from(String ownerId, long taskId, Path localFile) {
        String checksum = PptTemplateChecksum.sha256(localFile);
        return "ppt-artifact-" + taskId + "-" + checksum.substring(0, 24);
    }

    public static String objectKey(String ownerId, long taskId, Path localFile) {
        return "ppt/artifacts/" + from(ownerId, taskId, localFile) + ".pptx";
    }
}
