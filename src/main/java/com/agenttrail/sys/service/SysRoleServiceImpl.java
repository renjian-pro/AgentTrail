package com.agenttrail.sys.service;

import com.agenttrail.sys.entity.SysRole;
import com.agenttrail.sys.store.JdbcRoleStore;
import org.springframework.stereotype.Service;

import java.util.List;

/** 目前只转发 Store，但保持和 {@link SysUserService} 同一套"Controller 薄封装、业务逻辑在 Service"约定。 */
@Service
public class SysRoleServiceImpl implements SysRoleService {
    private final JdbcRoleStore roleStore;

    public SysRoleServiceImpl(JdbcRoleStore roleStore) { this.roleStore = roleStore; }

    @Override
    public List<SysRole> list() { return roleStore.findAll(); }
}
