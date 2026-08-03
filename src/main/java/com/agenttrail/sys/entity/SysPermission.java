package com.agenttrail.sys.entity;

/** 代码中 @SaCheckPermission 使用的权限点定义。 */
public record SysPermission(Long id, String code, String name, String module, long createdAt) {
}
