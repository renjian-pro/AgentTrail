package com.agenttrail.capability.analytics.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlSafetyGuardTest {
    private final SqlSafetyGuard guard = new SqlSafetyGuard(3, 200);

    @Test
    void acceptsReadOnlyQueriesAndNormalizesLimitOnAst() {
        ValidationResult result = guard.validate("SELECT * FROM rental");
        assertThat(result.valid()).isTrue();
        assertThat(result.safeSql().toLowerCase()).contains("limit 200");
    }

    @Test
    void rejectsDangerousNestedFunctionsButNotStringLiterals() {
        assertThat(guard.validate("SELECT CONCAT('LOAD_FILE()', name) FROM customer").valid()).isTrue();
        assertThat(guard.validate("SELECT CONCAT('x', LOAD_FILE('/etc/passwd')) FROM customer").reason())
                .contains("load_file");
        assertThat(guard.validate("SELECT LOAD_FILE/**/('/etc/passwd') FROM customer").valid()).isFalse();
        assertThat(guard.validate("SELECT * FROM rental WHERE id IN (SELECT SLEEP(1))").valid()).isFalse();
    }

    @Test
    void rejectsMutationMultiStatementAndUnstablePaging() {
        assertThat(guard.validate("UPDATE rental SET amount = 1").valid()).isFalse();
        assertThat(guard.validate("SELECT 1; DROP TABLE rental").valid()).isFalse();
        assertThat(guard.validate("SELECT * FROM rental LIMIT 10 OFFSET 100").reason())
                .contains("ORDER BY");
    }

    @Test
    void recursivelyChecksJoinLimits() {
        String sql = "SELECT * FROM (SELECT * FROM rental r1 JOIN rental r2 ON r1.id = r2.id) x";
        assertThat(guard.validate(sql).valid()).isTrue();
        String tooMany = "SELECT * FROM rental a JOIN rental b ON a.id=b.id JOIN rental c ON b.id=c.id "
                + "JOIN rental d ON c.id=d.id JOIN rental e ON d.id=e.id";
        assertThat(guard.validate(tooMany).reason()).contains("JOIN");
    }

    @Test
    void acceptsCtesAndUnionsWhileNormalizingTheWholeQueryLimit() {
        assertThat(guard.validate("WITH recent AS (SELECT * FROM rental) SELECT * FROM recent").valid()).isTrue();
        ValidationResult union = guard.validate("SELECT 1 UNION SELECT 2");
        assertThat(union.valid()).isTrue();
        assertThat(union.safeSql().toLowerCase()).contains("limit 200");
    }
}
