package com.agenttrail.loop.tools.idempotency;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** 用 MySQL 唯一键为审批恢复提供跨进程的工具调用去重记录。 */
public final class JdbcIdempotencyStore implements IdempotencyStore {

    private static final String INSERT_CLAIM_SQL = """
            INSERT IGNORE INTO agent_tool_idempotency
                (idempotency_key, owner_token, status, result, expires_at, updated_at)
            VALUES (?, ?, 'IN_FLIGHT', NULL, ?, ?)
            """;
    private static final String RECLAIM_EXPIRED_SQL = """
            UPDATE agent_tool_idempotency
               SET owner_token = ?, status = 'IN_FLIGHT', result = NULL, expires_at = ?, updated_at = ?
             WHERE idempotency_key = ? AND expires_at <= ?
            """;
    private static final String COMPLETE_SQL = """
            UPDATE agent_tool_idempotency
               SET status = 'COMPLETED', result = ?, expires_at = ?, updated_at = ?
             WHERE idempotency_key = ? AND owner_token = ? AND status = 'IN_FLIGHT'
            """;
    private static final String RELEASE_SQL = """
            DELETE FROM agent_tool_idempotency
             WHERE idempotency_key = ? AND owner_token = ? AND status = 'IN_FLIGHT'
            """;
    private static final String SELECT_SQL = """
            SELECT idempotency_key, status, result, updated_at, expires_at
              FROM agent_tool_idempotency
             WHERE idempotency_key = ?
            """;
    private static final String DELETE_EXPIRED_SQL = """
            DELETE FROM agent_tool_idempotency WHERE idempotency_key = ? AND expires_at <= ?
            """;

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public JdbcIdempotencyStore(@Qualifier("dataSource") DataSource dataSource) {
        this(dataSource, Clock.systemUTC());
    }

    JdbcIdempotencyStore(DataSource dataSource, Clock clock) {
        this.jdbcClient = JdbcClient.create(dataSource);
        this.clock = clock;
    }

    @Override
    public IdempotencyClaim claim(String key, Duration leaseTimeout) {
        while (true) {
            Instant now = clock.instant();
            Instant leaseExpiresAt = now.plus(leaseTimeout);
            String ownerToken = UUID.randomUUID().toString();
            int inserted = jdbcClient.sql(INSERT_CLAIM_SQL)
                    .param(key).param(ownerToken)
                    .param(Timestamp.from(leaseExpiresAt)).param(Timestamp.from(now)).update();
            if (inserted == 1) {
                return IdempotencyClaim.acquired(ownerToken);
            }
            int reclaimed = jdbcClient.sql(RECLAIM_EXPIRED_SQL)
                    .param(ownerToken).param(Timestamp.from(leaseExpiresAt)).param(Timestamp.from(now))
                    .param(key).param(Timestamp.from(now)).update();
            if (reclaimed == 1) {
                return IdempotencyClaim.acquired(ownerToken);
            }
            Optional<StoredRecord> existing = select(key);
            if (existing.isPresent()) {
                return IdempotencyClaim.occupied(existing.get().record());
            }
            // 另一个执行者可能恰好在 INSERT IGNORE 之后释放了占位；重新抢一次即可，
            // 不能把“记录瞬间消失”误判为我们已经拿到所有权。
        }
    }

    @Override
    public void complete(String key, String ownerToken, String result, Duration recordTtl) {
        Instant now = clock.instant();
        int updated = jdbcClient.sql(COMPLETE_SQL)
                .param(result).param(Timestamp.from(now.plus(recordTtl))).param(Timestamp.from(now))
                .param(key).param(ownerToken)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("幂等键 " + key + " 的租约所有权已失效，不能记录完成结果");
        }
    }

    @Override
    public void release(String key, String ownerToken) {
        jdbcClient.sql(RELEASE_SQL).param(key).param(ownerToken).update();
    }

    @Override
    public Optional<IdempotencyRecord> find(String key) {
        Instant now = clock.instant();
        Optional<StoredRecord> stored = select(key);
        if (stored.isEmpty() || stored.get().expiresAt().isAfter(now)) {
            return stored.map(StoredRecord::record);
        }
        jdbcClient.sql(DELETE_EXPIRED_SQL).param(key).param(Timestamp.from(now)).update();
        return Optional.empty();
    }

    private Optional<StoredRecord> select(String key) {
        return jdbcClient.sql(SELECT_SQL).param(key).query((row, ignored) -> {
            IdempotencyRecord record = new IdempotencyRecord(
                    row.getString("idempotency_key"),
                    IdempotencyRecord.Status.valueOf(row.getString("status")),
                    row.getString("result"),
                    row.getTimestamp("updated_at").toInstant());
            return new StoredRecord(record, row.getTimestamp("expires_at").toInstant());
        }).optional();
    }

    private record StoredRecord(IdempotencyRecord record, Instant expiresAt) {
    }
}
