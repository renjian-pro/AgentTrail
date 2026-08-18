package com.agenttrail.capability.ppt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 开发/测试用文件对象存储。它也使用稳定 key 和 checksum 复用对象；生产装配应替换为 MinIO 实现，
 * 状态机不依赖本机磁盘路径作为长期引用。
 */
public final class LocalPptArtifactStore implements PptArtifactStore {
    private final Path root;
    private final Map<String, PptArtifact> artifacts = new ConcurrentHashMap<>();

    public LocalPptArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public PptArtifact put(PptArtifactUpload upload) {
        try {
            if (!Files.isRegularFile(upload.localFile())) throw new IOException("产物文件不存在");
            String artifactId = PptArtifactId.from(upload.ownerId(), upload.taskId(), upload.localFile());
            String objectKey = upload.objectKey();
            Path target = root.resolve(objectKey).normalize();
            if (!target.startsWith(root)) throw new IOException("产物 object key 越界");
            Files.createDirectories(target.getParent());
            String sourceChecksum = PptTemplateChecksum.sha256(upload.localFile());
            if (!Files.isRegularFile(target) || !sourceChecksum.equals(PptTemplateChecksum.sha256(target))) {
                Files.copy(upload.localFile(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            PptArtifact artifact = new PptArtifact(artifactId, upload.ownerId(), upload.taskId(), objectKey,
                    PptTemplateChecksum.sha256(target), Files.size(target), upload.contentType(),
                    System.currentTimeMillis(), "ACTIVE");
            artifacts.put(artifactId, artifact);
            return artifact;
        } catch (IOException failure) {
            throw new PptGenerationException("保存 PPT 产物失败", failure);
        }
    }

    @Override
    public Optional<PptArtifact> find(String artifactId) {
        return Optional.ofNullable(artifacts.get(artifactId));
    }

    @Override
    public String signedDownloadUrl(String artifactId, Duration validity) {
        return find(artifactId).map(artifact -> root.resolve(artifact.objectKey()).toUri().toString())
                .orElseThrow(() -> new PptGenerationException("PPT 产物不存在: " + artifactId));
    }
}
