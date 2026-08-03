package com.agenttrail.sys;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.agenttrail.sys.entity.SysRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class SysRoleController {
    private final JdbcRoleStore roleStore;
    public SysRoleController(JdbcRoleStore roleStore) { this.roleStore = roleStore; }

    @GetMapping("/api/sys/roles")
    @SaCheckPermission("sys:role:view")
    public List<SysRole> list() { return roleStore.findAll(); }
}
