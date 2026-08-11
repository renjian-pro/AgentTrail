# Ticket F1／6：登录 / 鉴权守卫 / 顶栏身份展示 — 技术开发文档

> 派生自 [`frontend-phase2-auth.md`](frontend-phase2-auth.md)（v1 spec 的后续增量，同一个 Vue3+TS+Vite 应用，不新起项目）。`Blocked by` 后端 [Ticket 1](backend-phase2-auth-ticket-01.md)（需要 `/api/auth/login`/`logout`/`info` 三个接口）。后端 [Ticket 3](backend-phase2-auth-ticket-03.md) 依赖这一票——现有 `/agent/v1/**` 接口改成要求登录后，前端所有现有 API 调用（chat/file/ppt/research）都要靠这一票新增的 401 拦截逻辑才能正常工作，不然现有对话功能会直接因为 401 报错。

## 0. 范围边界

**这一票只做**：登录页、鉴权路由守卫、API client 的 token 注入与 401 统一处理、顶栏身份展示、角色只读列表（`RoleListView`，小页面，放这里而不是 F2，因为它不依赖用户管理 CRUD，只依赖 Ticket 1 的种子角色数据）。

**不做**：用户管理后台（F2）、部门树组件（F2）。

## 1. 前置事实（先读一遍现有代码，不要凭空设计）

- 项目现在**没有任何 UI 组件库**，纯手写组件 + `src/styles.css`，`frontend-phase2-auth.md` 明确要求不引入重量级依赖——新组件延续这个风格，不要引入 Element Plus/Ant Design Vue 之类的库
- API 请求走 `src/api/http.ts`（只有 5 行）：`request<T>(input, init)` 用原生 `fetch`，`jsonInit(body)` 拼 POST 请求体，**没有 axios，没有拦截器机制**——这一票要在这个文件里加拦截逻辑，不是引入 axios 重写
- 状态管理用 Pinia **setup 风格**（`defineStore('chat', () => {...})`，参照 `src/stores/chat.ts`），不是 options 风格
- 路由在 `src/router.ts`，目前只有 `/` 重定向到 `/chat` 和 `/chat` 一条路由，用 `createWebHistory`
- `App.vue` 左下角侧栏硬编码了 `<b>anonymous</b><small>本地工作区</small>`（`App.vue` 里 `sidebar-bottom` 那一块）——这一票要把它换成真实登录用户名，并加登出入口

## 2. 验收标准

- [ ] 访问任意页面，未登录时自动跳转到 `/login`（除 `/login` 本身）
- [ ] `/login` 用用户名密码登录成功后，跳转回登录前想访问的页面（`redirect` query 参数），未指定则跳 `/chat`
- [ ] 登录失败（账号密码错误 / 账号被禁用）显示明确错误提示，不是裸的 HTTP 状态码
- [ ] 刷新页面后登录态保持（token 存 `localStorage`，参照 `chat.ts` 里 `STORAGE_KEY` 的持久化模式）
- [ ] 任意 API 请求自动带上 `Authorization` 请求头
- [ ] 任意 API 请求收到 401，自动清空本地登录态并跳转 `/login`（不需要每个业务组件自己处理这个分支）
- [ ] 顶栏/侧栏展示当前用户名和角色，点击可登出
- [ ] `RoleListView` 展示角色列表和各自的数据范围（`ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF`），只读
- [ ] 现有 `/chat`（对话）功能登录后依然能正常使用（回归验证：这一票改了 `http.ts`，必须确认没有破坏 `chat-api.ts`/`file-api.ts`/`ppt-api.ts`/`research-api.ts` 现有调用）

## 3. 新增/修改文件清单

| 文件 | 改动 |
|---|---|
| `src/stores/auth.ts`（新增） | Pinia store（setup 风格，参照 `chat.ts`）：`token`/`currentUser`（`{ userId, username, nickname, roles }`）状态，`login(username, password)`/`logout()`/`fetchCurrentUser()` actions，token 持久化到 `localStorage`（单独一个 key，比如 `agenttrail.auth-token`，不要复用 `chat.ts` 的 `STORAGE_KEY`） |
| `src/api/auth-api.ts`（新增） | 封装 `POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/info` 三个调用，用 `http.ts` 现有的 `request`/`jsonInit`，不要引入新的请求封装方式 |
| `src/api/sys-api.ts`（新增，这一票只用到只读部分） | `GET /api/sys/roles` 封装，供 `RoleListView` 用 |
| `src/api/http.ts`（修改） | 见第 4 节，改动要小，不要重写整个文件 |
| `src/views/LoginView.vue`（新增） | 用户名密码表单 + 错误提示 |
| `src/views/RoleListView.vue`（新增） | 角色只读列表 |
| `src/components/UserBadge.vue`（新增） | 当前用户名/角色展示 + 登出按钮 |
| `src/router.ts`（修改） | 加 `/login` 路由 + `beforeEach` 守卫 |
| `src/App.vue`（修改） | `sidebar-bottom` 那一块换成 `<UserBadge />`，删掉硬编码的 `anonymous` |

## 4. `http.ts` 具体怎么改（精确到函数级别，不要重写）

现有内容只有 `request`/`jsonInit`/`toErrorMessage` 三个函数。改法：

```ts
// 在 request() 内部，发请求前从 auth store 或直接读 localStorage 取 token，
// 拼进 init.headers 的 Authorization 字段；不要用 Pinia store 实例（http.ts 是纯函数模块，
// 不在 Vue 组件树里，直接读 localStorage 的 token key，和 auth.ts store 用同一个 key 常量，
// 建议把这个 key 常量抽到一个两边都能 import 的地方，比如新增 src/api/auth-token.ts 只导出这一个常量）

// response.status === 401 时：清掉本地 token（localStorage.removeItem），
// 用 window.location.href = '/login' 做跳转（http.ts 拿不到 vue-router 实例，
// 不要在这里尝试注入 router，硬跳转是这个模块边界下最简单可靠的做法）
```

其余业务 API 文件（`chat-api.ts`/`file-api.ts`/`ppt-api.ts`/`research-api.ts`）**不需要改一行**——它们都是调用 `http.ts` 的 `request`/`jsonInit`，认证逻辑收在这一层，业务代码不用重复处理。这正是验收标准里"每个业务组件不用重复写鉴权逻辑"这条的落地方式。

## 5. 路由守卫

```ts
router.beforeEach((to) => {
  if (to.path === '/login') return true
  const authed = !!localStorage.getItem(TOKEN_KEY)   // 同第 4 节的 key 常量
  if (!authed) return { path: '/login', query: { redirect: to.fullPath } }
  return true
})
```

`LoginView` 登录成功后：`router.push((route.query.redirect as string) || '/chat')`。

## 6. 实现顺序

1. `auth-token.ts`（token key 常量）+ `auth-api.ts`，先写 `chat-api.spec.ts` 同等风格的 mock fetch 测试
2. `http.ts` 的 401/Authorization 改动，写测试覆盖"带 token 请求"和"收到 401 清空登录态"两个分支
3. `auth.ts` store，参照 `chat.spec.ts` 的测试风格
4. `LoginView.vue` + 路由守卫，`vitest` + `@vue/test-utils` 覆盖登录成功/失败/跳转三条路径
5. `UserBadge.vue` + `App.vue` 改动
6. `RoleListView.vue`（最独立，最后做也可以，不影响其它部分）

## 7. 明确禁止事项

- **不要**引入 axios 或任何 HTTP 客户端库替换 `http.ts` 现有的 `fetch` 封装
- **不要**引入 UI 组件库
- **不要**修改 `chat-api.ts`/`file-api.ts`/`ppt-api.ts`/`research-api.ts` 这四个业务 API 文件——它们不需要感知认证逻辑
- **不要**在 `http.ts`（纯模块）里尝试拿 Pinia store 实例或 vue-router 实例，用 `localStorage` 直读和硬跳转（第 4 节已说明）
- **不要**做"记住我"/多角色切换这类没在 user story 里出现的功能
