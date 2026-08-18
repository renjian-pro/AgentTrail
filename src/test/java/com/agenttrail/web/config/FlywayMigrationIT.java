package com.agenttrail.web.config;

import com.agenttrail.support.MySqlContainerTestSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/** 在空白真实 MySQL 上验证 V1→V2→V3 能按生产路径连续迁移。 */
@Testcontainers
class FlywayMigrationIT extends MySqlContainerTestSupport {

    @Test
    void allMigrationsHaveUniqueVersionsAndCreateTheIdempotencyFence() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        flyway.validate();

        JdbcClient jdbc = JdbcClient.create(createDataSource());
        assertThat(jdbc.sql("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE()
                   AND table_name = 'agent_tool_idempotency'
                   AND column_name = 'owner_token'
                """).query(Integer.class).single()).isEqualTo(1);
    }

    private static javax.sql.DataSource createDataSource() {
        org.springframework.jdbc.datasource.DriverManagerDataSource source =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return source;
    }
}
