package com.agenttrail.capability.analytics.schema;

import com.agenttrail.capability.analytics.config.AnalyticsSchemaProperties;
import com.agenttrail.loop.task.RedisTaskLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.TimeUnit;

/** M-Schema 的 Redis -> JVM -> 同步降级缓存。 */
public class MschemaCacheService {
    private static final Logger log = LoggerFactory.getLogger(MschemaCacheService.class);
    private static final String REDIS_KEY = "agenttrail:analytics:mschema:v1";

    private final MschemaIntrospector introspector;
    private final ObjectMapper objectMapper;
    private final AnalyticsSchemaProperties properties;
    private final RedissonClient redisson;
    private final RedisTaskLock refreshLock;
    private volatile Mschema local;

    public MschemaCacheService(MschemaIntrospector introspector,
                               ObjectMapper objectMapper,
                               AnalyticsSchemaProperties properties,
                               RedissonClient redisson,
                               RedisTaskLock refreshLock) {
        this.introspector = introspector;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisson = redisson;
        this.refreshLock = refreshLock;
        if (refreshLock != null) {
            refreshLock.startAutoRenewal();
        }
    }

    public Mschema get() {
        Mschema cached = readFromRedis();
        if (cached != null) {
            local = cached;
            return cached;
        }
        cached = local;
        if (cached != null) {
            return cached;
        }
        return refresh();
    }

    public Mschema snapshot() {
        return get();
    }

    @Scheduled(cron = "${agenttrail.analytics.schema.refresh-cron:0 0 3 * * *}")
    public void scheduledRefresh() {
        try {
            refresh();
        } catch (RuntimeException failure) {
            log.warn("M-Schema 定时刷新失败，保留旧缓存: {}", failure.getMessage());
        }
    }

    public synchronized Mschema refresh() {
        if (refreshLock != null && !refreshLock.tryAcquire("analytics-mschema-refresh")) {
            Mschema cached = readFromRedis();
            if (cached == null) cached = local;
            if (cached != null) {
                local = cached;
                return cached;
            }
            throw new IllegalStateException("M-Schema 正在由其他实例刷新，且当前没有可用缓存");
        }
        try {
            Mschema refreshed = introspector.introspect();
            local = refreshed;
            writeToRedis(refreshed);
            return refreshed;
        } finally {
            if (refreshLock != null) {
                try {
                    refreshLock.release("analytics-mschema-refresh");
                } catch (RuntimeException failure) {
                    log.debug("释放 M-Schema 刷新锁失败: {}", failure.getMessage());
                }
            }
        }
    }

    private Mschema readFromRedis() {
        if (redisson == null) {
            return null;
        }
        try {
            RBucket<String> bucket = redisson.getBucket(REDIS_KEY, StringCodec.INSTANCE);
            String json = bucket.get();
            return json == null || json.isBlank() ? null : objectMapper.readValue(json, Mschema.class);
        } catch (Exception failure) {
            log.warn("读取 M-Schema Redis 缓存失败，转同步自省: {}", failure.getMessage());
            return null;
        }
    }

    private void writeToRedis(Mschema schema) {
        if (redisson == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(schema);
            long ttl = Math.max(1, properties.getCacheTtlHours());
            redisson.getBucket(REDIS_KEY, StringCodec.INSTANCE)
                    .set(json, ttl, TimeUnit.HOURS);
        } catch (Exception failure) {
            log.warn("写入 M-Schema Redis 缓存失败: {}", failure.getMessage());
        }
    }
}
