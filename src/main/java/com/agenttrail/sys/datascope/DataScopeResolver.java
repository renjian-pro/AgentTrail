package com.agenttrail.sys.datascope;

import com.agenttrail.sys.JdbcDeptStore;
import com.agenttrail.sys.JdbcUserStore;
import com.agenttrail.sys.entity.SysRole;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/** 根据用户的全部角色和部门关系计算可见部门并集；不负责 SQL AST 改写。 */
@Service
public class DataScopeResolver {
    private final JdbcUserStore userStore;
    private final JdbcDeptStore deptStore;

    public DataScopeResolver(JdbcUserStore userStore, JdbcDeptStore deptStore) {
        this.userStore = userStore;
        this.deptStore = deptStore;
    }

    public DataScopeContext resolve(Long userId) {
        if (userId == null) throw new DataScopeResolutionException("userId 不能为空");
        List<SysRole> roles = userStore.findRolesByUserId(userId);
        if (roles.isEmpty()) throw new DataScopeResolutionException("用户没有任何角色，无法解析数据权限: " + userId);
        DataScope scope = roles.stream().map(role -> parseScope(role.dataScope()))
                .max(DataScope::compareByWidth).orElseThrow();
        if (scope == DataScope.ALL || scope == DataScope.SELF) {
            return new DataScopeContext(userId, scope, List.of());
        }
        if (!deptStore.isTreeLoaded()) throw new DataScopeResolutionException("部门数据未加载，无法解析数据权限");
        LinkedHashSet<Long> union = new LinkedHashSet<>();
        for (Long deptId : userStore.findDeptIdsByUserId(userId)) {
            if (scope == DataScope.DEPT_AND_SUB) union.addAll(deptStore.findSubtreeDeptIds(deptId));
            else union.add(deptId);
        }
        return new DataScopeContext(userId, scope, List.copyOf(union));
    }

    private static DataScope parseScope(String value) {
        try { return DataScope.valueOf(value); }
        catch (RuntimeException failure) { throw new DataScopeResolutionException("未知数据范围: " + value, failure); }
    }
}
