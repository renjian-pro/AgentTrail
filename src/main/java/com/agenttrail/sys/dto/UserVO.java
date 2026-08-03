package com.agenttrail.sys.dto;

import com.agenttrail.sys.entity.SysRole;
import com.agenttrail.sys.entity.SysUser;
import java.util.List;

/** 对外用户视图明确排除 password，避免实体演进时意外泄露凭据。 */
public record UserVO(Long id, String username, String nickname, String status, List<Long> roleIds,
        List<String> roleCodes, List<Long> deptIds, long createdAt, long updatedAt) {
    public static UserVO of(SysUser user, List<SysRole> roles, List<Long> deptIds) {
        return new UserVO(user.id(), user.username(), user.nickname(), user.status(),
                roles.stream().map(SysRole::id).toList(), roles.stream().map(SysRole::code).toList(),
                List.copyOf(deptIds), user.createdAt(), user.updatedAt());
    }
}
