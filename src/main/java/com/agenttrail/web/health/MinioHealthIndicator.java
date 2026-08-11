package com.agenttrail.web.health;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;
    private final String bucket;

    public MinioHealthIndicator(MinioClient minioClient,
            @Value("${agenttrail.ppt.image.minio-bucket}") String bucket) {
        this.minioClient = minioClient;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            return exists
                    ? Health.up().build()
                    : Health.down().withDetail("reason", "bucket 不存在: " + bucket).build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
