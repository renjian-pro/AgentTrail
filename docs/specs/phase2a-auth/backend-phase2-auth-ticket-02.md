# Ticket 2／6：用户管理 CRUD + 角色/部门只读接口 — 技术开发文档

> 派生自 [`backend-phase2-auth.md`](backend-phase2-auth.md)。`Blocked by` Ticket 1（[`backend-phase2-auth-ticket-01.md`](backend-phase2-auth-ticket-01.md)）——五张表、`JdbcUserStore`/`JdbcRoleStore`/`JdbcDeptStore`、`SaTokenConfig` 全局拦截器必须先落地。前端 Ticket F2（[`frontend-phase2-auth-ticket-f2.md`](frontend-phase2-auth-ticket-f2.md)）依赖这一票的接口。

## 0. 范围边界

**这一票只做**：用户的增删改查+启停+角色部门分配，角色/部门的**只读**查询接口（供前端下拉/树选择/`RoleListView` 用）。

**不做**：角色/部门的新增编辑删除（种子数据，`backend-phase2-auth.md` 的 Out of Scope 已定）、密码重置接口（不在任何一条 user story 里，真要做单开一张票）、`DataScopeResolver`（Ticket 4）、任何现有 Controller 的越权修复（Ticket 3）。

## 1. 验收标准

- [ ] `GET /api/sys/users?keyword=&page=&size=` 分页返回，`keyword` 匹配 `username`/`nickname`
- [ ] `POST /api/sys/users` 创建用户（`username`/`password`/`nickname`/`roleIds`/`deptIds`），`password` 入库前 BCrypt
- [ ] `PUT /api/sys/users/{id}` 编辑 `nickname`/`roleIds`/`deptIds`（不改 `username`/`password`）
- [ ] `PATCH /api/sys/users/{id}/status` 启用/禁用
- [ ] `DELETE /api/sys/users/{id}` 删除
- [ ] 禁用或删除一个用户时，如果这个用户是系统里**唯一**拥有 `admin` 角色的启用用户，接口拒绝并返回明确错误（不能让系统失去管理入口）
- [ ] `GET /api/sys/roles` 返回全部角色（只读）
- [ ] `GET /api/sys/depts` 返回部门树（只读，前端要嵌套结构不是平铺列表）
- [ ] 以上 `/api/sys/**` 全部接口非 `admin` 角色访问返回 403
- [ ] Testcontainers 集成测试覆盖：创建用户带多部门（验证 `sys_user_dept` 多条插入）、"删光最后一个 admin" 被拒绝、非 admin 访问 403

## 2. 和 Ticket 1 的职责边界（避免重复实现/冲突）

Ticket 1 的 `JdbcUserStore`/`JdbcRoleStore`/`JdbcDeptStore` 只需要有 `findByUsername`（登录用）和 `findById`（`/api/auth/info` 用）。**这一票在这三个 Store 上新增方法**，不要重新建一套 Store：

| Store | 这一票新增的方法 |
|---|---|
| `JdbcUserStore` | `search(String keyword, int page, int size) -> Page<SysUser>`、`insert(SysUser, String hashedPassword) -> Long`、`update(SysUser)`、`updateStatus(Long id, String status)`、`delete(Long id)`、`replaceRoles(Long userId, List<Long> roleIds)`（先删 `sys_user_role` 该用户全部记录再插入新的，简单可靠，用户角色数量不会大到需要 diff）、`replaceDepts(Long userId, List<Long> deptIds)`（同理）、`countActiveAdmins() -> long`（不含当前正在操作的这个用户，或者查询时排除法，具体看第 4 节） |
| `JdbcRoleStore` | `findAll() -> List<SysRole>` |
| `JdbcDeptStore` | `findAll() -> List<SysDept>`（前端自己拼树，或者这里直接拼好返回——见第 5 节） |

## 3. 新增 Java 文件

| 文件 | 职责 |
|---|---|
| `com/agenttrail/sys/SysUserController.java` | 五个用户管理接口，薄封装，业务逻辑在 Service |
| `com/agenttrail/sys/SysUserService.java` / `SysUserServiceImpl.java` | 创建/编辑/启停/删除的业务逻辑，含"最后一个 admin"校验 |
| `com/agenttrail/sys/SysRoleController.java` | `GET /api/sys/roles`，薄封装，业务逻辑在 `SysRoleService` |
| `com/agenttrail/sys/SysRoleService.java` | 目前只有 `findAll()` 转发 `JdbcRoleStore`；即使现在逻辑单薄也要建这一层——保持和 `SysUserController`/`LoginController` 同样的"Controller 薄封装、业务逻辑在 Service"约定，Ticket 5 会往这个类里加角色-权限分配的业务逻辑，不是新建一个类 |
| `com/agenttrail/sys/SysDeptController.java` | `GET /api/sys/depts`，薄封装，业务逻辑在 `SysDeptService` |
| `com/agenttrail/sys/SysDeptService.java` | **这一层不是可选的**：`JdbcDeptStore.findAll()` 查出的平铺列表转成 `DeptTreeNode` 树形结构，这是业务转换逻辑，必须放在 Service 里，不能留在 Controller 方法体里直接组装（一个类一个职责，Controller 不做数据转换） |
| `com/agenttrail/sys/dto/UserPageQuery.java`、`UserCreateRequest.java`、`UserUpdateRequest.java`、`UserVO.java`、`DeptTreeNode.java` | 请求/响应 DTO，不要把 `SysUser` 实体直接序列化返回（避免 `password` 字段泄漏），这几个 DTO 是必须的，不是可选的 |

## 4. "不能删光所有 admin" 的具体算法

不要写"数据库里 `role=admin` 的用户总数 > 1"这种简单判断——要判断的是**启用状态**的 admin 用户数，被禁用的 admin 不算数：

```sql
SELECT COUNT(DISTINCT u.id)
FROM sys_user u
JOIN sys_user_role ur ON ur.user_id = u.id
JOIN sys_role r ON r.id = ur.role_id
WHERE r.code = 'admin' AND u.status = 'ACTIVE' AND u.id != ?   -- 排除当前正在操作的用户
```

`SysUserServiceImpl.disable(userId)`/`delete(userId)`/`update(userId, ...)`（如果编辑操作会移除该用户的 admin 角色）在执行前，如果目标用户当前是启用状态的 admin，且上面这条 SQL 结果为 0，直接拒绝（业务异常，Controller 层转成 4xx + 明确错误信息，不要让它变成 500）。

## 5. 部门树的组装方式

`sys_dept` 表是平铺存储（`parent_id`/`ancestors`），`GET /api/sys/depts` 返回给前端时**后端拼好树形结构**再返回，不要把平铺列表甩给前端自己拼——前端只负责渲染，业务规则（谁是谁的子节点）在后端确定，这是 `frontend-phase2-auth.md` 里"权限/脱敏的展示原则"（后端算好，前端照渲染）这条原则的延伸，同样适用在这里。

`DeptTreeNode` 是一个自引用结构（`id`/`name`/`children: List<DeptTreeNode>`），组装方法是 `SysDeptService.findDeptTree()`：调 `JdbcDeptStore.findAll()` 查出平铺列表后，按 `parent_id` 在内存里组一遍树（顶级部门 `parent_id = 0`），几十个部门量级不需要考虑性能优化。`SysDeptController` 只调这一个方法，不自己碰 `JdbcDeptStore`。

## 6. 权限门禁

在 `SaTokenConfig`（Ticket 1 已建）里追加一条路由规则，不要新建一个拦截器类：

```java
SaRouter.match("/api/sys/**").check(r -> StpUtil.checkRoleOr("admin"));
```

Controller 方法上再加 `@SaCheckRole("admin")` 作为第二道声明（和 Ticket 1 全局登录拦截器同样的"双重保险"模式，不要用编程式的 `if (!isAdmin()) return 403` 手写判断——项目这次选的是"一步到位的完整 RBAC"，用 Sa-Token 标准注解比手写判断更少代码也更少出错）。

## 7. 实现顺序

1. `JdbcUserStore`/`JdbcRoleStore`/`JdbcDeptStore` 新增方法，每个方法先写 Testcontainers 测试
2. `SysUserServiceImpl` 的"最后一个 admin"校验逻辑，单独写测试覆盖：只有一个 admin 时禁用/删除被拒绝、有两个 admin 时可以操作其中一个
3. 五个用户管理接口 + 角色/部门只读接口，`@SpringBootTest` 集成测试覆盖增删改查全流程
4. 权限门禁：非 admin 账号（用 Ticket 1 种子数据里的 `analyst_test`）访问 `/api/sys/**` 返回 403 的测试

## 8. 明确禁止事项

- **不要**把 `SysUser` 实体直接作为接口返回体——必须过 `UserVO`，排除 `password` 字段
- **不要**做密码重置/修改接口——不在这一票范围
- **不要**做角色/部门的新增编辑删除——种子数据，只读
- **不要**用编程式 `if/else` 判断角色，用 `@SaCheckRole` + `SaRouter` 规则（第 6 节已说明原因）
- **不要**在 `replaceRoles`/`replaceDepts` 里做增量 diff（先查旧的再算差集再分别插入删除）——先整体删除该用户的全部关联记录，再插入新的，逻辑更简单，用户的角色/部门数量级不需要为性能做这个优化
- **不要**让 `SysRoleController`/`SysDeptController` 直接调用 `JdbcRoleStore`/`JdbcDeptStore`——一律经过 `SysRoleService`/`SysDeptService`，哪怕当前逻辑只是单纯转发，这是为了和 `SysUserController`/`LoginController` 保持同一套"Controller 薄封装、业务逻辑在 Service"的约定，不要因为逻辑简单就破例跳过这一层
- 其余共享约束（Testcontainers 不用 H2、不点名参考来源、注释用中文）同 Ticket 1，不再重复
