package com.agenttrail.sys.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.entity.SysRole;
import com.agenttrail.sys.service.SysRoleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SysRoleController {
    private final SysRoleService roleService;
    public SysRoleController(SysRoleService roleService) { this.roleService = roleService; }

    @GetMapping("/api/sys/roles")
    @SaCheckPermission("sys:role:view")
    public List<SysRole> list() { return roleService.list(); }
}
