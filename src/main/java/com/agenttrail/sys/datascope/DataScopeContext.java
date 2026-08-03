package com.agenttrail.sys.datascope;

import java.util.List;

/**
 * 数据权限解析的中间结果：ALL 不需要部门列表；SELF 的空列表表示调用方必须按业务 user_id 过滤；
 * DEPT/DEPT_AND_SUB 才使用 deptIds 拼接未来 SQL 的部门条件。
 */
public record DataScopeContext(Long userId, DataScope scope, List<Long> deptIds) {
    public DataScopeContext {
        deptIds = List.copyOf(deptIds == null ? List.of() : deptIds);
    }
}
