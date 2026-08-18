package com.agenttrail.capability.ppt;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.http.Method;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 生产对象存储实现。object key 由 artifactId 派生，应用重启后仍可通过 stat/presign 找回对象，
 * 不依赖某个实例的本地路径或内存 map。
 */
public final class MinioPptArtifactStore implements PptArtifactStore {
    private final MinioClient minio;
    private final String bucket;
    private final Map<String, PptArtifact> cache = new ConcurrentHashMap<>();

    public MinioPptArtifactStore(MinioClient minio, String bucket) {
        this.minio = minio;
        this.bucket = bucket;
    }

    @Override
    public PptArtifact put(PptArtifactUpload upload) {
        try {
            ensureBucket();
            String artifactId = PptArtifactId.from(upload.ownerId(), upload.taskId(), upload.localFile());
            long size = Files.size(upload.localFile());
            String checksum = PptTemplateChecksum.sha256(upload.localFile());
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(upload.objectKey())
                    .stream(Files.newInputStream(upload.localFile()), size, -1)
                    .contentType(upload.contentType()).build());
            PptArtifact artifact = new PptArtifact(artifactId, upload.ownerId(), upload.taskId(), upload.objectKey(),
                    checksum, size, upload.contentType(), System.currentTimeMillis(), "ACTIVE");
            cache.put(artifactId, artifact);
            return artifact;
        } catch (Exception failure) {
            throw new PptGenerationException("上传 PPT 产物到对象存储失败", failure);
        }
    }

    @Override
    public Optional<PptArtifact> find(String artifactId) {
        PptArtifact cached = cache.get(artifactId);
        if (cached != null) return Optional.of(cached);
        String objectKey = "ppt/artifacts/" + artifactId + ".pptx";
        try {
            var stat = minio.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            PptArtifact artifact = new PptArtifact(artifactId, "unknown", -1, objectKey,
                    stat.etag(), stat.size(), "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    0, "ACTIVE");
            cache.put(artifactId, artifact);
            return Optional.of(artifact);
        } catch (Exception missing) {
            return Optional.empty();
        }
    }

    @Override
    public String signedDownloadUrl(String artifactId, Duration validity) {
        PptArtifact artifact = find(artifactId)
                .orElseThrow(() -> new PptGenerationException("PPT 产物不存在: " + artifactId));
        try {
            return minio.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder().method(Method.GET)
                    .bucket(bucket).object(artifact.objectKey())
                    .expiry((int) validity.toSeconds(), java.util.concurrent.TimeUnit.SECONDS).build());
        } catch (Exception failure) {
            throw new PptGenerationException("生成 PPT 产物签名 URL 失败", failure);
        }
    }

    private void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
            minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
        }
    }
}
