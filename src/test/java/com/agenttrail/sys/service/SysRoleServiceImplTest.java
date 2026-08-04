package com.agenttrail.sys.service;

import com.agenttrail.sys.entity.SysRole;
import com.agenttrail.sys.store.JdbcRoleStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SysRoleServiceImplTest {

    @Test
    void forwardsToStore() {
        JdbcRoleStore store = mock(JdbcRoleStore.class);
        SysRole role = new SysRole(1L, "admin", "管理员", "ALL", 0, "ACTIVE", 1L, 1L);
        when(store.findAll()).thenReturn(List.of(role));

        assertThat(new SysRoleServiceImpl(store).list()).containsExactly(role);
    }
}
