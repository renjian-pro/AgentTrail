package com.agenttrail.sys.store;

import com.agenttrail.sys.entity.SysRole;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.beans.factory.annotation.Qualifier;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** 角色只读查询及 admin 角色定位。角色定义由 schema 种子维护，本轮不开放角色 CRUD。 */
public class JdbcRoleStore {
    private final JdbcClient jdbc;

    public JdbcRoleStore(@Qualifier("dataSource") DataSource dataSource) { this.jdbc = JdbcClient.create(dataSource); }

    public List<SysRole> findAll() {
        return jdbc.sql("SELECT id, code, name, data_scope, sort, status, created_at, updated_at FROM sys_role ORDER BY sort, id")
                .query(JdbcRoleStore::map).list();
    }

    public Optional<SysRole> findById(long id) {
        return jdbc.sql("SELECT id, code, name, data_scope, sort, status, created_at, updated_at FROM sys_role WHERE id = ?")
                .param(id).query(JdbcRoleStore::map).optional();
    }

    public Optional<SysRole> findByCode(String code) {
        return jdbc.sql("SELECT id, code, name, data_scope, sort, status, created_at, updated_at FROM sys_role WHERE code = ?")
                .param(code).query(JdbcRoleStore::map).optional();
    }

    private static SysRole map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SysRole(rs.getLong("id"), rs.getString("code"), rs.getString("name"), rs.getString("data_scope"),
                rs.getInt("sort"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
