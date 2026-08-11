# Ticket F3／8：角色权限分配界面 — 技术开发文档

> 派生自 [`frontend-phase2-auth.md`](frontend-phase2-auth.md) 的后续扩展。`Blocked by` 前端 [Ticket F1](frontend-phase2-auth-ticket-f1.md)（`RoleListView` 已存在，这一票在它基础上加编辑入口，不重建）和后端 [Ticket 5](backend-phase2-auth-ticket-05.md)（权限点接口）。

## 0. 范围边界

**这一票只做**：在 F1 已建的 `RoleListView` 上加"编辑权限"入口（仅 admin 可见/可用）+ 权限分配弹窗（按模块分组的复选框列表）。

**不做**：角色本身的新增/编辑/删除（still Out of Scope）、权限点定义本身的管理界面（权限点是种子数据，参照后端 Ticket 5 的边界）。

## 1. 验收标准

- [ ] `RoleListView` 每行（仅 admin 账号登录时）显示"编辑权限"按钮
- [ ] 点击后弹窗展示全部权限点，按 `module` 分组（用户管理/角色管理/部门管理），当前角色已有的权限点默认勾选
- [ ] 保存后调用后端接口覆盖式更新，成功后弹窗关闭、列表/权限状态刷新
- [ ] 非 admin 账号登录，`RoleListView` 不展示"编辑权限"入口（前端层面的隐藏，真正的边界由后端 `@SaCheckPermission` 保证，参照 F2 里"前端只做整页/入口级拦截，不做更细粒度"的既定原则）
- [ ] 后端返回错误时如实展示，不吞掉

## 2. 新增 / 修改文件

| 文件 | 改动 |
|---|---|
| `src/api/sys-api.ts`（扩展，F1/F2 已建） | 新增 `GET /api/sys/permissions`、`GET /api/sys/roles/{id}/permissions`、`PUT /api/sys/roles/{id}/permissions` 三个调用封装 |
| `src/views/RoleListView.vue`（修改，F1 已建） | 每行加"编辑权限"按钮（`v-if="authStore.currentUser?.roles.includes('admin')"`），点击打开 `RolePermissionDialog` |
| `src/components/RolePermissionDialog.vue`（新增） | 按模块分组的复选框列表 + 保存按钮 |

## 3. `RolePermissionDialog` 设计

```ts
// props: roleId: number
// 打开时：并行请求 GET /api/sys/permissions（全部权限点，按 module 分组）
//         和 GET /api/sys/roles/{roleId}/permissions（当前角色已有的权限码）
// 本地状态：Set<string>（当前勾选的权限码），初始值来自后者
// 保存：PUT /api/sys/roles/{roleId}/permissions，body 是勾选的权限码数组
```

分组渲染：用后端返回的 `module` 字段做分组标题，不要在前端硬编码模块名称列表（后端 Ticket 5 的权限点清单如果以后增删，前端不需要跟着改代码）。

组件测试：用固定 fixture（几个权限点，分属两个模块，其中一部分已勾选）测试渲染分组正确、勾选状态正确、保存时提交的权限码数组正确。

## 4. 实现顺序

1. `sys-api.ts` 扩展三个接口封装，mock fetch 测试
2. `RolePermissionDialog.vue`，用固定 fixture 做组件测试（不依赖真实后端）
3. `RoleListView.vue` 加入口按钮 + 弹窗联动

## 5. 明确禁止事项

- **不要**在前端维护一份权限码到中文名称的映射表——分组标题和权限名称全部用后端返回的 `name`/`module` 字段渲染，硬编码一份会导致前后端权限点增删时前端跟着漏改
- **不要**给权限点列表加搜索/分页——目前 8 个权限点的量级不需要
- **不要**做角色本身的编辑（这个弹窗只管权限点分配，不改角色的 `name`/`code`/`data_scope`）
