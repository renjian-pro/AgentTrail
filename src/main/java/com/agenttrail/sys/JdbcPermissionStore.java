package com.agenttrail.sys;

import com.agenttrail.sys.entity.SysPermission;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;

/** 权限点定义与角色权限覆盖式更新；权限点本身只由 schema 种子维护。 */
public class JdbcPermissionStore {
    private final JdbcClient jdbc;

    public JdbcPermissionStore(DataSource dataSource) { this.jdbc = JdbcClient.create(dataSource); }

    public List<SysPermission> findAll() {
        return jdbc.sql("SELECT id, code, name, module, created_at FROM sys_permission ORDER BY module, id")
                .query(JdbcPermissionStore::map).list();
    }

    public List<SysPermission> findByRoleId(long roleId) {
        return jdbc.sql("""
                SELECT p.id, p.code, p.name, p.module, p.created_at
                FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id = p.id
                WHERE rp.role_id = ? ORDER BY p.module, p.id
                """).param(roleId).query(JdbcPermissionStore::map).list();
    }

    public List<String> findCodesByUserId(long userId) {
        return jdbc.sql("""
                SELECT DISTINCT p.code FROM sys_permission p
                JOIN sys_role_permission rp ON rp.permission_id = p.id
                JOIN sys_user_role ur ON ur.role_id = rp.role_id
                WHERE ur.user_id = ? ORDER BY p.code
                """).param(userId).query((rs, rowNum) -> rs.getString("code")).list();
    }

    public void replaceRolePermissions(long roleId, List<Long> permissionIds) {
        jdbc.sql("DELETE FROM sys_role_permission WHERE role_id = ?").param(roleId).update();
        if (permissionIds == null) return;
        long now = System.currentTimeMillis();
        for (Long permissionId : permissionIds.stream().distinct().toList()) {
            jdbc.sql("INSERT INTO sys_role_permission (role_id, permission_id, created_at) VALUES (?, ?, ?)")
                    .param(roleId).param(permissionId).param(now).update();
        }
    }

    private static SysPermission map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SysPermission(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("module"), rs.getLong("created_at"));
    }
}
