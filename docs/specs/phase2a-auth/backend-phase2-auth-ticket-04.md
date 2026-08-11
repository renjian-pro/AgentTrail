# Ticket 4／6：DataScopeResolver 数据范围解析 — 技术开发文档

> 派生自 [`backend-phase2-auth.md`](backend-phase2-auth.md)。`Blocked by` Ticket 1。和 Ticket 2/Ticket 3 都不相关，可以并行做。**这一票产出一个纯粹的、和任何具体业务表都无关的能力**：给一个 `userId`，算出它能看到哪些 `deptId`。真正拿这个结果去改写 SQL WHERE 条件，是未来 Phase 2 SQL 能力包的消费方，不在这一票范围。

## 0. 范围边界

**这一票只做**：`DataScopeResolver.resolve(userId) -> DataScopeContext`。不做 SQL 改写、不做 AST 解析、不碰任何业务数据表（sakila 或其它）。

## 1. 验收标准

- [ ] `data_scope = ALL` 的用户，`resolve` 返回一个"全部可见"标记（不需要具体部门列表）
- [ ] `data_scope = SELF` 的用户，同样返回"全部可见"标记不适用——返回空部门列表，调用方需要按约定理解为"只能看自己"（见第 3 节的返回值语义）
- [ ] `data_scope = DEPT` 的用户，返回自己挂载的部门 id 列表（不含子部门）
- [ ] `data_scope = DEPT_AND_SUB` 的用户，返回自己挂载的部门 + 所有子部门 id 列表
- [ ] 多角色用户取所有角色里 `data_scope` 最宽的一个（宽窄顺序：`ALL` > `DEPT_AND_SUB` > `DEPT` > `SELF`）
- [ ] 跨部门用户（Ticket 1 种子数据里的 `cross_analyst`，挂载两个部门）解析结果是两个部门各自展开后的**并集**，不是只取第一个
- [ ] 用户没有任何角色，或部门树数据缺失（`sys_dept` 表为空/未加载）时，`resolve` 必须抛异常而不是返回"全部可见"或"空"——fail-closed，不能默认放行
- [ ] Testcontainers 集成测试覆盖以上每一条

## 2. 新增 Java 文件

| 文件 | 职责 |
|---|---|
| `com/agenttrail/sys/datascope/DataScope.java` | 枚举：`ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF`，带一个"是否比另一个更宽"的比较方法（用枚举声明顺序或者显式优先级字段实现，不要用字符串比较） |
| `com/agenttrail/sys/datascope/DataScopeContext.java` | 结果对象：`(Long userId, DataScope scope, List<Long> deptIds)`，record |
| `com/agenttrail/sys/datascope/DataScopeResolver.java` | 核心解析逻辑 |

`DataScopeResolver` 依赖 Ticket 1/2 已经建好的 `JdbcUserStore`（查用户角色）和 `JdbcDeptStore`（查部门树），**不要重新写查询逻辑**，如果这两个 Store 缺少需要的方法（比如"查一个用户挂载的全部 `deptId`"、"按 `ancestors` 前缀查子树"），在这两个 Store 上补方法，不要绕开它们直接在 `DataScopeResolver` 里写 SQL。

## 3. `resolve` 的具体算法

```java
public DataScopeContext resolve(Long userId) {
    List<SysRole> roles = userStore.findRolesByUserId(userId);
    if (roles.isEmpty()) {
        throw new IllegalStateException("用户没有任何角色，无法解析数据权限：userId=" + userId);
    }
    DataScope scope = roles.stream()
        .map(r -> DataScope.valueOf(r.dataScope()))
        .max(DataScope::compareByWidth)   // 取最宽的
        .orElseThrow();

    if (scope == DataScope.ALL || scope == DataScope.SELF) {
        return new DataScopeContext(userId, scope, List.of());
    }

    if (!deptStore.isTreeLoaded()) {   // 具体判断方式：查 sys_dept 表是否为空，或者用一个显式的"已加载"标记，二选一，选更简单的那个
        throw new IllegalStateException("部门数据未加载，无法解析数据权限");
    }

    List<Long> userDeptIds = userStore.findDeptIdsByUserId(userId);
    Set<Long> union = new LinkedHashSet<>();
    for (Long deptId : userDeptIds) {
        if (scope == DataScope.DEPT_AND_SUB) {
            union.addAll(deptStore.findSubtreeDeptIds(deptId));   // 按 ancestors LIKE '本部门 ancestors 前缀,%' + 自身
        } else {
            union.add(deptId);
        }
    }
    return new DataScopeContext(userId, scope, List.copyOf(union));
}
```

`findSubtreeDeptIds(deptId)` 的实现：先查出 `deptId` 自己的 `ancestors` 字段拼上自己的 id 得到"本部门的完整路径前缀"，再用 `WHERE ancestors LIKE '前缀,%' OR id = ?` 查出所有子孙部门 + 自己。不要用递归 CTE（Ticket 1 建表时已经说明理由）。

## 4. 返回值语义（调用方要知道怎么解读）

`DataScopeContext` 不是"最终能直接拼进 SQL 的东西"，是一个中间结果，调用方（未来的 SQL 改写逻辑）要按 `scope` 分支处理：

- `scope == ALL`：不加任何过滤条件
- `scope == SELF`：不看 `deptIds`，改成按业务表自己的 `user_id` 字段过滤（"只看自己经手的数据"，不是部门维度）
- `scope == DEPT` 或 `DEPT_AND_SUB`：用 `deptIds` 过滤业务表的 `dept_id` 字段

这一票**不实现**上面这个分支消费逻辑，只需要在 Javadoc 里把这四种语义写清楚，让消费方（未来的票）不用重新猜。

## 5. 实现顺序

1. `DataScope` 枚举 + 宽窄比较方法，先写单元测试（`ALL` 比 `DEPT_AND_SUB` 宽，等等）
2. `JdbcUserStore`/`JdbcDeptStore` 补充所需方法，每个方法先写 Testcontainers 测试
3. `DataScopeResolver.resolve`，用 Ticket 1 种子数据（`admin`/`mgr_test`/`analyst_test`/`cross_analyst`）逐个场景写集成测试
4. fail-closed 两种场景（无角色、部门树未加载）单独写测试

## 6. 明确禁止事项

- **不要**在这一票里写任何 SQL WHERE 改写逻辑、AST 解析、或者接触任何业务数据表——这一票的产出就是 `DataScopeContext`，到此为止
- **不要**用字符串比较判断 `data_scope` 哪个更宽（`"ALL".equals(...)` 这类）——用枚举 + 显式优先级
- **不要**在部门树数据缺失时返回"全部可见"作为兜底——必须抛异常，这是安全边界，不是可以从简的地方
- 其余共享约束同 Ticket 1
