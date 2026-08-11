package com.agenttrail.web.health;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthIndicatorTest {

    @Test
    void reportsRedisAsUnknownWhenTheOptionalClientIsNotConfigured() {
        ObjectProvider<org.redisson.api.RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);

        assertThat(new RedisHealthIndicator(provider).health().getStatus()).isEqualTo(Status.UNKNOWN);
    }

    @Test
    void reportsPgVectorDownWhenTheProbeFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).thenThrow(new IllegalStateException("offline"));

        assertThat(new PgVectorHealthIndicator(jdbcTemplate).health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void reportsMinioDownWhenTheConfiguredBucketIsMissing() throws Exception {
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(org.mockito.ArgumentMatchers.any())).thenReturn(false);

        assertThat(new MinioHealthIndicator(minioClient, "agenttrail-ppt-images").health().getStatus())
                .isEqualTo(Status.DOWN);
    }

    @Test
    void reportsDashScopeUpOnlyWhenAnApiKeyExists() {
        assertThat(new DashScopeHealthIndicator("key").health().getStatus()).isEqualTo(Status.UP);
        assertThat(new DashScopeHealthIndicator(" ").health().getStatus()).isEqualTo(Status.DOWN);
    }
}
