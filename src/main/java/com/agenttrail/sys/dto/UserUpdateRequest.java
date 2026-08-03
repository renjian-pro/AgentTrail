package com.agenttrail.sys.dto;

import java.util.List;

public record UserUpdateRequest(String nickname, List<Long> roleIds, List<Long> deptIds) {
}
