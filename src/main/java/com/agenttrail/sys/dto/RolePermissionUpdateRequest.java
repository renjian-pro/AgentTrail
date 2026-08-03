package com.agenttrail.sys.dto;

import java.util.List;

public record RolePermissionUpdateRequest(List<Long> permissionIds) {
}
