package com.agenttrail.auth.config;

import cn.dev33.satoken.stp.StpInterface;
import com.agenttrail.sys.store.JdbcPermissionStore;
import com.agenttrail.sys.store.JdbcUserStore;
import org.springframework.stereotype.Component;

import java.util.List;

/** Sa-Token 的角色/权限事实适配器；每次校验实时读数据库，角色变更无需重新登录。 */
@Component
public class SysStpInterface implements StpInterface {
    private final JdbcUserStore userStore;
    private final JdbcPermissionStore permissionStore;

    public SysStpInterface(JdbcUserStore userStore, JdbcPermissionStore permissionStore) {
        this.userStore = userStore;
        this.permissionStore = permissionStore;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return userStore.findRolesByUserId(Long.parseLong(String.valueOf(loginId))).stream()
                .map(r -> r.code()).toList();
    }

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return permissionStore.findCodesByUserId(Long.parseLong(String.valueOf(loginId)));
    }
}
