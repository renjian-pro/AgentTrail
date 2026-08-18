package com.agenttrail.loop.tools.idempotency;

import com.agenttrail.support.MySqlContainerTestSupport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证租约过期抢占时，MySQL owner token 能隔离迟到的旧执行者。 */
@Testcontainers
class JdbcIdempotencyStoreIT extends MySqlContainerTestSupport {

    private static DataSource dataSource;
    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @BeforeAll
    static void createSchema() {
        dataSource = createDataSourceAndSchema();
    }

    @BeforeEach
    void resetTable() {
        JdbcClient.create(dataSource).sql("TRUNCATE TABLE agent_tool_idempotency").update();
    }

    @Test
    void expiredOwnerCannotCompleteOrReleaseTheReplacementOwnersLease() {
        Duration lease = Duration.ofSeconds(5);
        Duration ttl = Duration.ofHours(1);
        JdbcIdempotencyStore oldProcess = new JdbcIdempotencyStore(dataSource, clock);
        JdbcIdempotencyStore replacement = new JdbcIdempotencyStore(dataSource, clock);

        IdempotencyClaim oldCompleteOwner = oldProcess.claim("charge:complete", lease);
        clock.advance(lease.plusSeconds(1));
        IdempotencyClaim replacementCompleteOwner = replacement.claim("charge:complete", lease);

        assertThat(replacementCompleteOwner.acquired()).isTrue();
        assertThatThrownBy(() -> oldProcess.complete(
                "charge:complete", oldCompleteOwner.ownerToken(), "stale", ttl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("所有权");
        assertThat(replacement.find("charge:complete")).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyRecord.Status.IN_FLIGHT);
        replacement.complete("charge:complete", replacementCompleteOwner.ownerToken(), "fresh", ttl);
        assertThat(replacement.find("charge:complete")).get()
                .extracting(IdempotencyRecord::result).isEqualTo("fresh");

        IdempotencyClaim oldReleaseOwner = oldProcess.claim("charge:release", lease);
        clock.advance(lease.plusSeconds(1));
        IdempotencyClaim replacementReleaseOwner = replacement.claim("charge:release", lease);
        oldProcess.release("charge:release", oldReleaseOwner.ownerToken());

        assertThat(replacement.find("charge:release")).get()
                .extracting(IdempotencyRecord::status).isEqualTo(IdempotencyRecord.Status.IN_FLIGHT);
        replacement.complete("charge:release", replacementReleaseOwner.ownerToken(), "fresh", ttl);
        assertThat(replacement.find("charge:release")).get()
                .extracting(IdempotencyRecord::result).isEqualTo("fresh");
    }
}
