package com.agenttrail.sys.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.dto.RolePermissionUpdateRequest;
import com.agenttrail.sys.entity.SysPermission;
import com.agenttrail.sys.service.SysPermissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 权限点查询与角色权限覆盖式更新，仅作用于管理面；业务逻辑（含事务边界）在 {@link SysPermissionService}。 */
@RestController
public class SysPermissionController {
    private final SysPermissionService permissionService;

    public SysPermissionController(SysPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping("/api/sys/permissions")
    @SaCheckPermission("sys:role:view")
    public Map<String, List<SysPermission>> all() { return permissionService.allGroupedByModule(); }

    @GetMapping("/api/sys/roles/{id}/permissions")
    @SaCheckPermission("sys:role:view")
    public List<SysPermission> byRole(@PathVariable long id) { return permissionService.byRole(id); }

    @PutMapping("/api/sys/roles/{id}/permissions")
    @SaCheckPermission("sys:role:manage-permission")
    public List<SysPermission> replace(@PathVariable long id, @RequestBody RolePermissionUpdateRequest request) {
        return permissionService.replace(id, request == null ? List.of() : request.permissionIds());
    }
}
