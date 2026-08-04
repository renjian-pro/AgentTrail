package com.agenttrail.sys.datascope;

import com.agenttrail.sys.store.JdbcDeptStore;
import com.agenttrail.sys.store.JdbcUserStore;
import com.agenttrail.sys.entity.SysRole;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataScopeResolverTest {
    private final JdbcUserStore userStore = mock(JdbcUserStore.class);
    private final JdbcDeptStore deptStore = mock(JdbcDeptStore.class);
    private final DataScopeResolver resolver = new DataScopeResolver(userStore, deptStore);

    @Test
    void takesTheWidestRoleAndReturnsAllWithoutNeedingDepartmentRows() {
        when(userStore.findRolesByUserId(1L)).thenReturn(List.of(role(1L, "SELF"), role(2L, "ALL")));

        assertThat(resolver.resolve(1L)).isEqualTo(new DataScopeContext(1L, DataScope.ALL, List.of()));
    }

    @Test
    void expandsEveryDepartmentAndUnionsTheSubtrees() {
        when(userStore.findRolesByUserId(2L)).thenReturn(List.of(role(2L, "DEPT_AND_SUB")));
        when(userStore.findDeptIdsByUserId(2L)).thenReturn(List.of(10L, 20L));
        when(deptStore.isTreeLoaded()).thenReturn(true);
        when(deptStore.findSubtreeDeptIds(10L)).thenReturn(List.of(10L, 11L));
        when(deptStore.findSubtreeDeptIds(20L)).thenReturn(List.of(20L, 21L));

        assertThat(resolver.resolve(2L).deptIds()).containsExactly(10L, 11L, 20L, 21L);
    }

    @Test
    void failsClosedWhenRoleOrDepartmentTreeIsUnavailable() {
        when(userStore.findRolesByUserId(3L)).thenReturn(List.of());
        assertThatThrownBy(() -> resolver.resolve(3L)).isInstanceOf(DataScopeResolutionException.class);

        when(userStore.findRolesByUserId(4L)).thenReturn(List.of(role(3L, "DEPT")));
        when(deptStore.isTreeLoaded()).thenReturn(false);
        assertThatThrownBy(() -> resolver.resolve(4L)).isInstanceOf(DataScopeResolutionException.class);
    }

    private static SysRole role(long id, String scope) {
        return new SysRole(id, "r" + id, "role", scope, 1, "ACTIVE", 1L, 1L);
    }
}
