package com.agenttrail.sys.service;

import com.agenttrail.sys.entity.SysPermission;

import java.util.List;
import java.util.Map;

public interface SysPermissionService {
    Map<String, List<SysPermission>> allGroupedByModule();
    List<SysPermission> byRole(long roleId);
    List<SysPermission> replace(long roleId, List<Long> permissionIds);
}
