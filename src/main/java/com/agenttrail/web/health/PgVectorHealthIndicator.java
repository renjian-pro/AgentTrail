package com.agenttrail.web.health;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component("pgvector")
public class PgVectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;

    public PgVectorHealthIndicator(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Health.up().build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
