# Ticket 5／8：RBAC 权限点系统（sys_permission + 接口级权限校验）— 技术开发文档

> 派生自 [`backend-phase2-auth.md`](backend-phase2-auth.md) 的后续扩展（原 spec 定的范围里这块是 Out of Scope，这一票是后来追加的）。`Blocked by` [Ticket 2](backend-phase2-auth-ticket-02.md)（`#45`）——这一票会**修改** Ticket 2 已经写好的 `SysUserController`/`SysRoleController`/`SysDeptController` 上的权限注解，把粗粒度的 `@SaCheckRole("admin")` 换成细粒度的 `@SaCheckPermission("...")`。

## 0. 为什么要做这个，边界在哪

现状（Ticket 1/2）只有一个二元判断："是不是 admin"，`admin` 能做什么是写死在代码里的路由规则，不是数据驱动的。这一票把它换成标准 RBAC 第三层——`用户→角色→权限点`，权限点和角色的对应关系存在数据库里，可以不改代码调整"某个角色能做哪些操作"。

**范围只覆盖 `/api/sys/**`（用户/角色/部门管理这一侧）**。`/agent/v1/**`（对话/文件/PPT/DeepResearch）不引入权限点校验——这些接口面向所有登录用户，没有任何 user story 要求"某些角色不能用对话功能"，这些接口的访问控制到 Ticket #46（越权修复）为止就是完整的，不要在这一票里顺手给它们加权限校验。

## 1. 验收标准

- [ ] `sys_permission`/`sys_role_permission` 两张表建好，种子数据：`admin` 角色拥有全部权限点，其余三个种子角色（manager/analyst/employee）不拥有任何 `sys:*` 权限点（效果上和现状一致，只是从硬编码变成数据驱动）
- [ ] `SysStpInterface.getPermissionList` 真正实现（之前 Ticket 1 里先返回的是空列表）
- [ ] `SysUserController`/`SysRoleController`/`SysDeptController` 的接口注解从 `@SaCheckRole("admin")` 换成对应的 `@SaCheckPermission` 权限码
- [ ] `GET /api/sys/permissions` 返回全部权限点（按 `module` 分组）
- [ ] `GET /api/sys/roles/{id}/permissions` 返回该角色当前拥有的权限码列表
- [ ] `PUT /api/sys/roles/{id}/permissions` 覆盖式更新该角色的权限点分配
- [ ] 用一个只有 `sys:user:view` 权限、没有 `sys:user:delete` 权限的自定义测试角色验证：能查看用户列表，删除用户被拒绝
- [ ] 集成测试覆盖：给某角色新增/移除权限点后，该角色下的用户下一次请求立刻按新权限生效（不需要重新登录——Sa-Token 默认每次校验都实时查 `StpInterface`，不缓存，这一点只需要验证，不需要额外实现缓存失效逻辑）

## 2. 权限点清单（这一票的种子数据，即"代码里实际检查的权限码"）

| 权限码 | 名称 | 模块 | 对应接口 |
|---|---|---|---|
| `sys:user:view` | 查看用户 | 用户管理 | `GET /api/sys/users` |
| `sys:user:create` | 新增用户 | 用户管理 | `POST /api/sys/users` |
| `sys:user:update` | 编辑用户 | 用户管理 | `PUT /api/sys/users/{id}` |
| `sys:user:manage-status` | 启用/禁用用户 | 用户管理 | `PATCH /api/sys/users/{id}/status` |
| `sys:user:delete` | 删除用户 | 用户管理 | `DELETE /api/sys/users/{id}` |
| `sys:role:view` | 查看角色 | 角色管理 | `GET /api/sys/roles`、`GET /api/sys/permissions`、`GET /api/sys/roles/{id}/permissions` |
| `sys:role:manage-permission` | 分配角色权限 | 角色管理 | `PUT /api/sys/roles/{id}/permissions` |
| `sys:dept:view` | 查看部门 | 部门管理 | `GET /api/sys/depts` |

权限码是这一票**唯一的权威来源**——不要在其它地方（前端、文档）另起一套编码规则，前端 Ticket F3 直接消费这张表返回的数据，不需要自己维护一份权限码列表。

## 3. 建表 SQL（追加到 `schema.sql`，风格同 Ticket 1）

```sql
CREATE TABLE IF NOT EXISTS sys_permission
(
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(64) NOT NULL COMMENT '权限码，如 sys:user:create，业务代码里 @SaCheckPermission 直接引用这个值',
    name       VARCHAR(64) NOT NULL COMMENT '权限名称，管理界面展示用',
    module     VARCHAR(32) NOT NULL COMMENT '所属模块，界面按此分组展示，如 用户管理/角色管理/部门管理',
    created_at BIGINT      NOT NULL COMMENT '创建时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '权限点定义表，权限码和代码里的 @SaCheckPermission 一一对应';

CREATE TABLE IF NOT EXISTS sys_role_permission
(
    role_id       BIGINT NOT NULL COMMENT '角色 id',
    permission_id BIGINT NOT NULL COMMENT '权限点 id',
    created_at    BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    PRIMARY KEY (role_id, permission_id),
    KEY idx_sys_role_permission_permission (permission_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '角色-权限点关联表';
```

## 4. 新增 / 修改 Java 文件

| 文件 | 改动 |
|---|---|
| `com/agenttrail/sys/entity/SysPermission.java`（新增） | 权限点实体 |
| `com/agenttrail/sys/JdbcPermissionStore.java`（新增） | `findAll()`、`findByRoleId(Long roleId) -> List<SysPermission>`、`replaceRolePermissions(Long roleId, List<Long> permissionIds)`（先删该角色全部关联记录再插入新的，和 Ticket 2 的 `replaceRoles`/`replaceDepts` 同一个模式） |
| `com/agenttrail/auth/SysStpInterface.java`（修改，Ticket 1 已建） | 实现 `getPermissionList`：查 `sys_user_role` 拿角色 id 列表 → 查 `sys_role_permission`/`sys_permission` 拿权限码，去重合并（用户可多角色，权限点取并集，不是取交集——只要有一个角色给了这个权限点就算有） |
| `com/agenttrail/sys/SysPermissionService.java`（新增） | `listAll()`（转发 `JdbcPermissionStore.findAll`）、`listByRole(roleId)`、`replaceRolePermissions(roleId, permissionIds)`——`SysPermissionController` 一律经这一层，不直接碰 `JdbcPermissionStore`，和 Ticket 2 的 `SysUserService` 是同一个约定（Ticket 2 落地时如果 `SysRoleService` 还只是单纯转发 `findAll`，从这一票起它会变得有实质内容：这里新增的角色-权限分配相关方法可以直接放进 `SysRoleService`，不必再单独建一个 `SysPermissionService`——两种分法都行，选一种就不要混用，本票默认按上面这张表的 `SysPermissionService` 走） |
| `com/agenttrail/sys/SysPermissionController.java`（新增） | `GET /api/sys/permissions`、`GET /api/sys/roles/{id}/permissions`、`PUT /api/sys/roles/{id}/permissions`，薄封装，业务逻辑在 `SysPermissionService` |
| `com/agenttrail/sys/SysUserController.java`（修改，Ticket 2 已建） | 五个方法上的 `@SaCheckRole("admin")` 逐一换成对应权限码的 `@SaCheckPermission("sys:user:xxx")`（对照第 2 节表格） |
| `com/agenttrail/sys/SysRoleController.java`（修改） | `@SaCheckRole("admin")` 换成 `@SaCheckPermission("sys:role:view")` |
| `com/agenttrail/sys/SysDeptController.java`（修改） | 换成 `@SaCheckPermission("sys:dept:view")` |
| `com/agenttrail/auth/SaTokenConfig.java`（修改） | 移除 Ticket 2 加的 `SaRouter.match("/api/sys/**").check(checkRoleOr("admin"))` 这条粗粒度路由规则——细粒度校验完全交给各 Controller 方法上的 `@SaCheckPermission` 注解，不要两层规则同时存在，否则谁生效说不清楚 |

## 5. 实现顺序

1. 建表 + 种子数据（admin 角色关联全部 8 个权限点），先写纯 SQL 层测试
2. `JdbcPermissionStore`，每个方法先写测试
3. `SysStpInterface.getPermissionList` 实现，测试验证：给测试用户的角色分配/移除权限点后，`StpUtil.getPermissionList()` 返回结果同步变化
4. `SysPermissionController` 三个接口，测试覆盖分配权限后立即生效
5. 逐个把 `SysUserController`/`SysRoleController`/`SysDeptController` 的注解换掉，每换一个跑一次对应的既有测试（Ticket 2 写的测试这时候要留着，验证换注解没有改变现有 admin 账号的可用性）
6. 最后写一个"权限不足"的负向测试：自定义一个只有 `sys:user:view` 的角色，验证删除用户被拒绝、查看用户列表放行

## 6. 明确禁止事项

- **不要**给 `/agent/v1/**` 任何接口加权限点校验——这一票范围只在 `/api/sys/**`（第 0 节已说明）
- **不要**做权限点本身的动态新增/编辑/删除接口——权限点和代码里的 `@SaCheckPermission` 是一一对应的，只能随代码变更同步维护，是种子数据，不开放管理界面
- **不要**让 `SaRouter` 的粗粒度规则和 Controller 方法上的细粒度注解同时存在（第 4 节已说明，移除旧规则）
- **不要**引入权限的"继承"或"通配符"机制（比如 `sys:user:*` 匹配所有 `sys:user:xxx`）——用户 story 没有这个需求，8 个权限点手动列全，不需要模式匹配这层复杂度
- **不要**让 `SysPermissionController` 直接调 `JdbcPermissionStore`——经过 `SysPermissionService`（第 4 节已说明），Ticket 2 里 `SysRoleController`/`SysDeptController` 跳过 Service 层直连 Store 是一个已知要改掉的问题，这一票不要重蹈覆辙
- 其余共享约束（`JdbcClient`、Testcontainers、中文注释、不点名来源）同 Ticket 1
