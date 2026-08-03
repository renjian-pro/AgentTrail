package com.agenttrail.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.dto.RolePermissionUpdateRequest;
import com.agenttrail.sys.entity.SysPermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 权限点查询与角色权限覆盖式更新，仅作用于管理面。 */
@RestController
public class SysPermissionController {
    private final JdbcPermissionStore permissionStore;
    private final JdbcRoleStore roleStore;

    public SysPermissionController(JdbcPermissionStore permissionStore, JdbcRoleStore roleStore) {
        this.permissionStore = permissionStore; this.roleStore = roleStore;
    }

    @GetMapping("/api/sys/permissions")
    @SaCheckPermission("sys:role:view")
    public Map<String, List<SysPermission>> all() {
        Map<String, List<SysPermission>> grouped = new LinkedHashMap<>();
        for (SysPermission permission : permissionStore.findAll()) {
            grouped.computeIfAbsent(permission.module(), ignored -> new java.util.ArrayList<>()).add(permission);
        }
        return grouped;
    }

    @GetMapping("/api/sys/roles/{id}/permissions")
    @SaCheckPermission("sys:role:view")
    public List<SysPermission> byRole(@PathVariable long id) {
        roleStore.findById(id).orElseThrow(() -> new SysUserBusinessException("角色不存在: " + id));
        return permissionStore.findByRoleId(id);
    }

    @PutMapping("/api/sys/roles/{id}/permissions")
    @SaCheckPermission("sys:role:manage-permission")
    public List<SysPermission> replace(@PathVariable long id, @RequestBody RolePermissionUpdateRequest request) {
        roleStore.findById(id).orElseThrow(() -> new SysUserBusinessException("角色不存在: " + id));
        permissionStore.replaceRolePermissions(id, request == null ? List.of() : request.permissionIds());
        return permissionStore.findByRoleId(id);
    }
}
