package com.agenttrail.sys.entity;

/** 部门平铺节点；ancestors 是用于子树查询的稳定路径快照。 */
public record SysDept(Long id, String name, Long parentId, String ancestors, int sort, String status,
        long createdAt, long updatedAt) {
}
