# Ticket F2／6：用户管理后台（含部门树组件）— 技术开发文档

> 派生自 [`frontend-phase2-auth.md`](frontend-phase2-auth.md)。`Blocked by` 前端 [Ticket F1](frontend-phase2-auth-ticket-f1.md)（需要登录态、`http.ts` 的鉴权改动、路由守卫框架）和后端 [Ticket 2](backend-phase2-auth-ticket-02.md)（用户管理 CRUD + 角色/部门只读接口）。

## 0. 范围边界

**这一票只做**：管理员可见的用户管理页面（列表/搜索/分页/新增/编辑/启停/删除）+ 部门树可复用组件。

**不做**：角色/部门结构本身的增删改（后端就没开放，`backend-phase2-auth.md` Out of Scope 已定）、任何和 SQL 分析业务相关的渲染（那是 Phase 2 SQL 能力包自己的前端票，不在这次范围）。

## 1. 验收标准

- [ ] `/admin/users` 路由，只有 `admin` 角色能进（非 admin 访问跳转到无权限提示或首页，不是白屏）
- [ ] 用户列表分页展示，按用户名/昵称搜索
- [ ] 新增用户弹窗：用户名/密码/昵称输入 + 角色多选 + 部门树多选
- [ ] 编辑用户弹窗：昵称/角色/部门可改（用户名不可改，参照后端 Ticket 2 的接口约束）
- [ ] 启用/禁用按钮，操作后列表状态即时更新
- [ ] 删除按钮 + 二次确认弹窗
- [ ] 后端返回"不能删光所有 admin"这类错误时，前端如实展示错误信息，不是吞掉或者显示通用的"操作失败"
- [ ] `DeptTree` 组件做成独立可复用组件（勾选/展开逻辑独立于 `UserFormDialog`），支持多级嵌套展开/收起/多选

## 2. 新增文件

| 文件 | 职责 |
|---|---|
| `src/api/sys-api.ts`（扩展 F1 已建的文件，不新建） | 补充 `GET /api/sys/users`（带分页参数）、`POST /api/sys/users`、`PUT /api/sys/users/{id}`、`PATCH /api/sys/users/{id}/status`、`DELETE /api/sys/users/{id}`、`GET /api/sys/depts` |
| `src/stores/userManagement.ts` | Pinia store（setup 风格），持有用户列表/分页状态/搜索关键字，动作调 `sys-api.ts` |
| `src/views/UserManagementView.vue` | 列表页：搜索框 + 表格 + 分页 + 新增按钮 + 每行的编辑/启停/删除操作 |
| `src/components/UserFormDialog.vue` | 新增/编辑共用的弹窗表单，内部用 `DeptTree` |
| `src/components/DeptTree.vue` | 独立可复用组件：接收 `nodes: DeptTreeNode[]`（后端已经拼好树，不是前端自己拼），`v-model` 双向绑定选中的 `deptId[]`，内部管理展开/收起状态 |
| `src/router.ts`（修改） | 加 `/admin/users` 路由，`meta: { requiresAdmin: true }`，`beforeEach` 里追加对这个 meta 的判断（复用 F1 已经建立的登录守卫框架，追加一层角色判断，不要另写一个独立的守卫机制） |

## 3. `DeptTree` 组件设计

```ts
type DeptTreeNode = { id: number; name: string; children: DeptTreeNode[] }

// props: nodes: DeptTreeNode[], modelValue: number[]（当前选中的 deptId 列表）
// emit: update:modelValue
```

组件内部自己管理每个节点的展开/收起（本地 `ref<Set<number>>` 存已展开的节点 id），选中逻辑是简单多选（勾选父节点**不**自动勾选全部子节点——`frontend-phase2-auth.md` 的 user story 里没有这个联动要求，不要自己加，加了反而会让"一个用户挂载哪些具体部门"这件事变得不直观，和后端 `sys_user_dept` 存的是"精确挂载哪几个部门"这个语义对不上）。

组件测试：用一个 3 层的固定 fixture 测试展开/收起、勾选/取消勾选、`modelValue` 双向绑定。

## 4. 路由守卫的角色判断

F1 已经建立了"未登录跳 `/login`"的 `beforeEach` 守卫，这一票在同一个守卫函数里追加一层，不要新建第二个 `beforeEach`：

```ts
router.beforeEach((to) => {
  if (to.path === '/login') return true
  const authed = !!localStorage.getItem(TOKEN_KEY)
  if (!authed) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.requiresAdmin && !authStore.currentUser?.roles.includes('admin')) {
    return { path: '/chat' }   // 或者一个专门的"无权限"提示页，二选一，不需要太复杂
  }
  return true
})
```

（这里需要在 `router.ts` 里能访问到 `auth.ts` store 的当前用户角色信息，Pinia store 在路由守卫里的标准用法是 `useAuthStore()` 直接调用，不需要额外传参。）

前端这层角色判断只是"不让普通用户看到入口"，真正的权限边界在后端 Ticket 2 的 `@SaCheckRole("admin")`——`frontend-phase2-auth.md` 的 Out of Scope 已经写明"按钮级细粒度权限控制"不在范围内，这里的判断粒度就是"整个页面能不能进"，不需要做得更细。

## 5. 实现顺序

1. `sys-api.ts` 扩展（用户 CRUD + 部门树接口封装），mock fetch 测试
2. `DeptTree.vue`，独立组件测试（固定 fixture，不依赖真实后端）
3. `userManagement.ts` store
4. `UserFormDialog.vue`（新增/编辑共用），组件测试覆盖表单校验（用户名必填、新增时密码必填、编辑时不显示密码字段）
5. `UserManagementView.vue`（列表+搜索+分页+操作按钮）
6. 路由守卫追加角色判断

## 6. 明确禁止事项

- **不要**给 `DeptTree` 加"勾选父节点自动勾选子节点"这类联动逻辑（第 3 节已说明原因）
- **不要**自己在前端拼部门树结构——后端 Ticket 2 已经返回树形 JSON，`DeptTree` 只负责渲染
- **不要**做用户名/密码的编辑入口（后端 Ticket 2 的接口本来就不支持改这两个字段）
- **不要**新建第二个路由守卫函数——在 F1 已有的 `beforeEach` 里追加判断
- **不要**引入 UI 组件库或表格/树形组件的第三方依赖，手写（延续 F1 和整个项目的风格）
