package com.agenttrail.capability.analytics.schema;

import com.agenttrail.capability.analytics.config.AnalyticsSchemaProperties;
import com.agenttrail.loop.task.RedisTaskLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class MschemaCacheServiceTest {
    @Test
    void keepsTheLastSuccessfulSnapshotWhenRedisIsNotConfigured() {
        Mschema snapshot = new Mschema("agenttrail", java.util.List.of());
        MschemaIntrospector introspector = mock(MschemaIntrospector.class);
        when(introspector.introspect()).thenReturn(snapshot);
        MschemaCacheService cache = new MschemaCacheService(introspector, new ObjectMapper(),
                new AnalyticsSchemaProperties(), null, null);

        assertThat(cache.get()).isEqualTo(snapshot);
        assertThat(cache.get()).isEqualTo(snapshot);
        verify(introspector, times(1)).introspect();
    }

    @Test
    void doesNotIntrospectWhenAnotherInstanceOwnsAColdRefresh() {
        MschemaIntrospector introspector = mock(MschemaIntrospector.class);
        RedisTaskLock lock = mock(RedisTaskLock.class);
        when(lock.tryAcquire("analytics-mschema-refresh")).thenReturn(false);
        MschemaCacheService cache = new MschemaCacheService(introspector, new ObjectMapper(),
                new AnalyticsSchemaProperties(), null, lock);

        assertThatThrownBy(cache::refresh)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("其他实例");
        verify(introspector, never()).introspect();
    }
}
