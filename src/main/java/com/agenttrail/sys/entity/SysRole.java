package com.agenttrail.sys.entity;

/** 角色及其数据范围；权限点通过 sys_role_permission 单独关联。 */
public record SysRole(Long id, String code, String name, String dataScope, int sort, String status,
        long createdAt, long updatedAt) {
}
