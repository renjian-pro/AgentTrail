package com.agenttrail.auth.dto;

import com.agenttrail.sys.entity.SysUser;
import java.util.List;

public record UserInfo(Long id, String username, String nickname, List<String> roles) {
    public static UserInfo of(SysUser user, List<String> roles) {
        return new UserInfo(user.id(), user.username(), user.nickname(), List.copyOf(roles));
    }
}
