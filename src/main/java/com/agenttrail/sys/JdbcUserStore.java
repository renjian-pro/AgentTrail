package com.agenttrail.sys;

import com.agenttrail.sys.entity.SysRole;
import com.agenttrail.sys.entity.SysUser;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/** 用户、用户角色和用户部门的 JDBC 存取；所有写操作集中在这里保证关系表更新口径一致。 */
public class JdbcUserStore {

    private static final String SELECT_USER = """
            SELECT id, username, password, nickname, status, created_at, updated_at
            FROM sys_user WHERE id = ?
            """;
    private static final String SELECT_USERNAME = """
            SELECT id, username, password, nickname, status, created_at, updated_at
            FROM sys_user WHERE username = ?
            """;
    private static final String SELECT_ROLES = """
            SELECT r.id, r.code, r.name, r.data_scope, r.sort, r.status, r.created_at, r.updated_at
            FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = ? AND r.status = 'ACTIVE' ORDER BY r.sort, r.id
            """;
    private static final String SELECT_DEPTS = "SELECT dept_id FROM sys_user_dept WHERE user_id = ? ORDER BY dept_id";
    private static final String INSERT_USER = """
            INSERT INTO sys_user (username, password, nickname, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String COUNT_USERS = "SELECT COUNT(*) FROM sys_user WHERE (? = '' OR username LIKE ? OR nickname LIKE ?)";
    private static final String SEARCH_USERS = """
            SELECT id, username, password, nickname, status, created_at, updated_at FROM sys_user
            WHERE (? = '' OR username LIKE ? OR nickname LIKE ?) ORDER BY id DESC LIMIT ? OFFSET ?
            """;
    private static final String COUNT_OTHER_ADMINS = """
            SELECT COUNT(DISTINCT u.id) FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id JOIN sys_role r ON r.id = ur.role_id
            WHERE r.code = 'admin' AND u.status = 'ACTIVE' AND u.id <> ?
            """;

    private final JdbcClient jdbc;

    public JdbcUserStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public Optional<SysUser> findById(long id) {
        return jdbc.sql(SELECT_USER).param(id).query(JdbcUserStore::mapUser).optional();
    }

    public Optional<SysUser> findByUsername(String username) {
        return jdbc.sql(SELECT_USERNAME).param(username).query(JdbcUserStore::mapUser).optional();
    }

    public List<SysRole> findRolesByUserId(long userId) {
        return jdbc.sql(SELECT_ROLES).param(userId).query(JdbcUserStore::mapRole).list();
    }

    public List<Long> findDeptIdsByUserId(long userId) {
        return jdbc.sql(SELECT_DEPTS).param(userId).query((rs, rowNum) -> rs.getLong("dept_id")).list();
    }

    public SysUserPage search(String keyword, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.clamp(size, 1, 100);
        String value = keyword == null ? "" : keyword.trim();
        String pattern = "%" + value + "%";
        long total = jdbc.sql(COUNT_USERS).param(value).param(pattern).param(pattern)
                .query(Long.class).single();
        List<SysUser> users = jdbc.sql(SEARCH_USERS).param(value).param(pattern).param(pattern)
                .param(safeSize).param(safePage * safeSize).query(JdbcUserStore::mapUser).list();
        return new SysUserPage(users, total, safePage, safeSize);
    }

    public long insert(String username, String hashedPassword, String nickname, String status) {
        long now = System.currentTimeMillis();
        KeyHolder holder = new GeneratedKeyHolder();
        jdbc.sql(INSERT_USER).param(username).param(hashedPassword).param(nickname).param(status)
                .param(now).param(now).update(holder);
        return holder.getKey().longValue();
    }

    public void updateProfile(long id, String nickname) {
        jdbc.sql("UPDATE sys_user SET nickname = ?, updated_at = ? WHERE id = ?")
                .param(nickname).param(System.currentTimeMillis()).param(id).update();
    }

    public void updateStatus(long id, String status) {
        jdbc.sql("UPDATE sys_user SET status = ?, updated_at = ? WHERE id = ?")
                .param(status).param(System.currentTimeMillis()).param(id).update();
    }

    public void delete(long id) {
        jdbc.sql("DELETE FROM sys_user_dept WHERE user_id = ?").param(id).update();
        jdbc.sql("DELETE FROM sys_user_role WHERE user_id = ?").param(id).update();
        jdbc.sql("DELETE FROM sys_user WHERE id = ?").param(id).update();
    }

    public void replaceRoles(long userId, List<Long> roleIds) {
        jdbc.sql("DELETE FROM sys_user_role WHERE user_id = ?").param(userId).update();
        if (roleIds == null) return;
        long now = System.currentTimeMillis();
        for (Long roleId : roleIds.stream().distinct().toList()) {
            jdbc.sql("INSERT INTO sys_user_role (user_id, role_id, created_at) VALUES (?, ?, ?)")
                    .param(userId).param(roleId).param(now).update();
        }
    }

    public void replaceDepts(long userId, List<Long> deptIds) {
        jdbc.sql("DELETE FROM sys_user_dept WHERE user_id = ?").param(userId).update();
        if (deptIds == null) return;
        long now = System.currentTimeMillis();
        for (Long deptId : deptIds.stream().distinct().toList()) {
            jdbc.sql("INSERT INTO sys_user_dept (user_id, dept_id, created_at) VALUES (?, ?, ?)")
                    .param(userId).param(deptId).param(now).update();
        }
    }

    public long countOtherActiveAdmins(long userId) {
        return jdbc.sql(COUNT_OTHER_ADMINS).param(userId).query(Long.class).single();
    }

    public boolean hasAdminRole(long userId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM sys_user_role ur JOIN sys_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = 'admin'
                """).param(userId).query(Long.class).single() > 0;
    }

    public void replaceRolesAndDepts(long userId, List<Long> roleIds, List<Long> deptIds) {
        replaceRoles(userId, roleIds);
        replaceDepts(userId, deptIds);
    }

    private static SysUser mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SysUser(rs.getLong("id"), rs.getString("username"), rs.getString("password"),
                rs.getString("nickname"), rs.getString("status"), rs.getLong("created_at"), rs.getLong("updated_at"));
    }

    private static SysRole mapRole(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new SysRole(rs.getLong("id"), rs.getString("code"), rs.getString("name"),
                rs.getString("data_scope"), rs.getInt("sort"), rs.getString("status"),
                rs.getLong("created_at"), rs.getLong("updated_at"));
    }
}
