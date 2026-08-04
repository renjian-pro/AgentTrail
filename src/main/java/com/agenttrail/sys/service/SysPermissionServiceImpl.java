package com.agenttrail.sys.service;

import com.agenttrail.sys.entity.SysPermission;
import com.agenttrail.sys.exception.SysUserBusinessException;
import com.agenttrail.sys.store.JdbcPermissionStore;
import com.agenttrail.sys.store.JdbcRoleStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 权限点查询与角色权限覆盖式更新。
 *
 * <p>{@link #replace} 必须是 {@code @Transactional}：{@link JdbcPermissionStore#replaceRolePermissions}
 * 内部先 DELETE 整个角色的旧关联，再逐条 INSERT 新关联——两条独立语句。没有事务包裹时，
 * 中途失败（比如权限 id 列表里混进一个非法值）会让角色停在"权限被清空、新集合没插完"的
 * 半成品状态，这是真实的数据损坏风险，不是理论问题（回归测试见 {@link SysPermissionServiceIT}）。
 */
@Service
public class SysPermissionServiceImpl implements SysPermissionService {
    private final JdbcPermissionStore permissionStore;
    private final JdbcRoleStore roleStore;

    public SysPermissionServiceImpl(JdbcPermissionStore permissionStore, JdbcRoleStore roleStore) {
        this.permissionStore = permissionStore;
        this.roleStore = roleStore;
    }

    @Override
    public Map<String, List<SysPermission>> allGroupedByModule() {
        Map<String, List<SysPermission>> grouped = new LinkedHashMap<>();
        for (SysPermission permission : permissionStore.findAll()) {
            grouped.computeIfAbsent(permission.module(), ignored -> new ArrayList<>()).add(permission);
        }
        return grouped;
    }

    @Override
    public List<SysPermission> byRole(long roleId) {
        requireRole(roleId);
        return permissionStore.findByRoleId(roleId);
    }

    @Override
    @Transactional
    public List<SysPermission> replace(long roleId, List<Long> permissionIds) {
        requireRole(roleId);
        permissionStore.replaceRolePermissions(roleId, permissionIds == null ? List.of() : permissionIds);
        return permissionStore.findByRoleId(roleId);
    }

    private void requireRole(long roleId) {
        roleStore.findById(roleId).orElseThrow(() -> new SysUserBusinessException("角色不存在: " + roleId));
    }
}
