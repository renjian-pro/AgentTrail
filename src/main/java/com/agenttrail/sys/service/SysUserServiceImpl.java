package com.agenttrail.sys.service;

import com.agenttrail.sys.dto.SysUserPage;
import com.agenttrail.sys.dto.UserCreateRequest;
import com.agenttrail.sys.dto.UserUpdateRequest;
import com.agenttrail.sys.entity.SysUser;
import com.agenttrail.sys.exception.SysUserBusinessException;
import com.agenttrail.sys.store.JdbcRoleStore;
import com.agenttrail.sys.store.JdbcUserStore;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 用户管理业务规则集中处：特别是最后一个启用 admin 的保护不能散落在 Controller。 */
@Service
public class SysUserServiceImpl implements SysUserService {
    private final JdbcUserStore userStore;
    private final JdbcRoleStore roleStore;
    private final BCryptPasswordEncoder passwordEncoder;

    public SysUserServiceImpl(JdbcUserStore userStore, JdbcRoleStore roleStore, BCryptPasswordEncoder passwordEncoder) {
        this.userStore = userStore;
        this.roleStore = roleStore;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public SysUserPage search(String keyword, int page, int size) { return userStore.search(keyword, page, size); }

    @Override
    @Transactional
    public SysUser create(UserCreateRequest request) {
        if (request == null || request.username() == null || request.username().isBlank()
                || request.password() == null || request.password().isBlank()) {
            throw new SysUserBusinessException("用户名和密码不能为空");
        }
        if (userStore.findByUsername(request.username().trim()).isPresent()) {
            throw new SysUserBusinessException("用户名已存在");
        }
        long id = userStore.insert(request.username().trim(), passwordEncoder.encode(request.password()),
                request.nickname(), "ACTIVE");
        userStore.replaceRolesAndDepts(id, request.roleIds(), request.deptIds());
        return userStore.findById(id).orElseThrow();
    }

    @Override
    @Transactional
    public SysUser update(long id, UserUpdateRequest request) {
        SysUser current = require(id);
        var roles = request == null || request.roleIds() == null ? userStore.findRolesByUserId(id)
                .stream().map(r -> r.id()).toList() : request.roleIds();
        if (current.active() && userStore.hasAdminRole(id) && !containsAdminRole(roles)
                && userStore.countOtherActiveAdmins(id) == 0) {
            throw new SysUserBusinessException("系统至少需要保留一个启用的 admin 账号，禁止移除最后一个 admin 角色");
        }
        userStore.updateProfile(id, request == null ? current.nickname() : request.nickname());
        userStore.replaceRolesAndDepts(id, roles,
                request == null || request.deptIds() == null ? userStore.findDeptIdsByUserId(id) : request.deptIds());
        return require(id);
    }

    @Override
    @Transactional
    public void updateStatus(long id, String status) {
        SysUser current = require(id);
        String next = normalizeStatus(status);
        if (current.active() && "DISABLED".equals(next) && userStore.hasAdminRole(id)
                && userStore.countOtherActiveAdmins(id) == 0) {
            throw new SysUserBusinessException("系统至少需要保留一个启用的 admin 账号，禁止禁用最后一个 admin");
        }
        userStore.updateStatus(id, next);
    }

    @Override
    @Transactional
    public void delete(long id) {
        SysUser current = require(id);
        if (current.active() && userStore.hasAdminRole(id) && userStore.countOtherActiveAdmins(id) == 0) {
            throw new SysUserBusinessException("系统至少需要保留一个启用的 admin 账号，禁止删除最后一个 admin");
        }
        userStore.delete(id);
    }

    private SysUser require(long id) {
        return userStore.findById(id).orElseThrow(() -> new SysUserBusinessException("用户不存在: " + id));
    }

    private static String normalizeStatus(String status) {
        if (!"ACTIVE".equals(status) && !"DISABLED".equals(status)) {
            throw new SysUserBusinessException("用户状态只能是 ACTIVE 或 DISABLED");
        }
        return status;
    }

    private boolean containsAdminRole(java.util.List<Long> roleIds) {
        return roleStore.findByCode("admin").map(role -> roleIds.contains(role.id())).orElse(false);
    }
}
