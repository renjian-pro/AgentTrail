package com.agenttrail.sys;

import com.agenttrail.sys.entity.SysDept;
import org.springframework.jdbc.core.simple.JdbcClient;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** 部门树的平铺查询；子树使用 ancestors 前缀，避免递归查询在权限判定链路中失控。 */
public class JdbcDeptStore {
    private final JdbcClient jdbc;

    public JdbcDeptStore(DataSource dataSource) { this.jdbc = JdbcClient.create(dataSource); }

    public List<SysDept> findAll() {
        return jdbc.sql("SELECT id, name, parent_id, ancestors, sort, status, created_at, updated_at FROM sys_dept ORDER BY sort, id")
                .query(JdbcDeptStore::map).list();
    }

    public Optional<SysDept> findById(long id) {
        return jdbc.sql("SELECT id, name, parent_id, ancestors, sort, status, created_at, updated_at FROM sys_dept WHERE id = ?")
                .param(id).query(JdbcDeptStore::map).optional();
    }

    public boolean isTreeLoaded() {
        return jdbc.sql("SELECT COUNT(*) FROM sys_dept WHERE status = 'ACTIVE'").query(Long.class).single() > 0;
    }

    public List<Long> findSubtreeDeptIds(long deptId) {
        SysDept root = findById(deptId).orElseThrow(() -> new IllegalStateException("部门不存在: " + deptId));
        String prefix = root.ancestors() + "," + root.id();
        return jdbc.sql("SELECT id FROM sys_dept WHERE id = ? OR ancestors LIKE CONCAT(?, ',%') ORDER BY id")
                .param(deptId).param(prefix).query((rs, rowNum) -> rs.getLong("id")).list();
    }

    private static SysDept map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SysDept(rs.getLong("id"), rs.getString("name"), rs.getLong("parent_id"), rs.getString("ancestors"),
                rs.getInt("sort"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
