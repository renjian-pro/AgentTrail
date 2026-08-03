package com.agenttrail.sys.dto;

import java.util.List;

public record UserCreateRequest(String username, String password, String nickname,
        List<Long> roleIds, List<Long> deptIds) {
}
