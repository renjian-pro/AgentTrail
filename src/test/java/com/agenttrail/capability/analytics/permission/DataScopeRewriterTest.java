package com.agenttrail.capability.analytics.permission;

import com.agenttrail.sys.datascope.DataScope;
import com.agenttrail.sys.datascope.DataScopeContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DataScopeRewriterTest {
    private final DataScopeRewriter rewriter = new DataScopeRewriter(new PermissionRuleRegistry(
            new DefaultPermissionRule(),
            new NoPermissionRule(Set.of("customer", "dim_dept"))));

    @Test
    void wrapsExistingOrBeforeAddingScope() {
        String sql = rewriter.rewrite("SELECT * FROM rental WHERE status = 1 OR amount > 100",
                new DataScopeContext(6L, DataScope.DEPT, java.util.List.of(3L, 4L)));
        assertThat(sql).contains("dept_id IN (3, 4)");
        assertThat(sql).contains("(status = 1 OR amount > 100)");
    }

    @Test
    void putsOuterJoinRightConditionIntoOn() {
        String sql = rewriter.rewrite(
                "SELECT c.customer_id FROM customer c LEFT JOIN rental r ON r.customer_id = c.customer_id",
                new DataScopeContext(6L, DataScope.DEPT, java.util.List.of(3L)));
        assertThat(sql.toLowerCase()).contains("r.customer_id = c.customer_id)")
                .contains("r.dept_id in (3)");
        assertThat(sql.toLowerCase()).doesNotContain("where r.dept_id");
    }

    @Test
    void skipsCteAliasAndRewritesCteBody() {
        String sql = rewriter.rewrite(
                "WITH recent AS (SELECT * FROM rental) SELECT * FROM recent",
                new DataScopeContext(6L, DataScope.SELF, java.util.List.of()));
        assertThat(sql.toLowerCase()).contains("rental.user_id = 6");
        assertThat(sql.toLowerCase()).doesNotContain("recent.user_id");
    }

    @Test
    void failsClosedForUnknownTableAndEmptyDepartmentScope() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewriter.rewrite(
                "SELECT * FROM unknown_table", new DataScopeContext(6L, DataScope.SELF, java.util.List.of())))
                .isInstanceOf(DataScopeRewriteException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rewriter.rewrite(
                "SELECT * FROM rental", new DataScopeContext(6L, DataScope.DEPT, java.util.List.of())))
                .isInstanceOf(DataScopeRewriteException.class);
    }
}
