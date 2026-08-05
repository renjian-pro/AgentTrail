# Ticket F5／18：Schema 浏览 + 术语字典页（含配套只读接口）— 技术开发文档

> GitHub issue: [#61](https://github.com/renjian-pro/AgentTrail/issues/61)

> 派生自 [`frontend-phase2-sql-dataagent.md`](frontend-phase2-sql-dataagent.md)。`Blocked by` 后端 [Ticket 7](backend-phase2-sql-ticket-07.md)（`SchemaProvider`）和 [Ticket 8](backend-phase2-sql-ticket-08.md)（`GlossaryCatalog`）。**不依赖** [F4](frontend-phase2-sql-ticket-f4.md)，可并行。
>
> **这是一张全栈票**：它需要两个后端只读接口，而这两个接口不属于前面任何一张后端票的范围。接口很薄（各不到 20 行，直接暴露已有的 Provider/Catalog），放在这里一起做比单开一张后端票更合理。

## 0. 范围边界

**这一票只做**：两个后端只读查询接口 + 两个前端只读页面。

**这一票不做**：任何编辑能力（Schema 是数据库自省出来的、术语字典是 git 管理的 YAML，都不该从 Web 界面改）、SQL 执行、对话相关的任何东西（F4）。

## 1. 为什么需要这两个页面

用户面对一个自然语言查询框时最常见的困境是"我能问什么"。没有这两个页面，用户只能靠试——问一句、发现库里没这个数据、再换一句，几轮下来对系统失去信任。

术语字典页还有一个隐性价值：它让用户看到"活跃客户"这个词在系统里的**权威定义**。用户看到定义后可能会说"我们业务上说的活跃不是这个意思"——这个反馈非常宝贵，是术语字典迭代的主要来源。

## 2. 后端部分

### 2.1 两个接口

| 接口 | 返回 | 实现 |
|---|---|---|
| `GET /api/analytics/schema` | 全部表 + 字段详情 | 调 Ticket 7 的 `SchemaProvider` |
| `GET /api/analytics/glossary` | 全部术语 | 调 Ticket 8 的 `GlossaryCatalog.allEntries()` |

### 2.2 返回结构化 JSON，不是给模型看的那段文本

Ticket 7 的 `SchemaProvider` 返回的是**给模型看的紧凑文本**（`(字段名: 类型, 注释, Examples: [...])`）。前端需要的是结构化数据（要渲染成表格、要能折叠/搜索）。

**做法**：给 `MschemaCacheService` 加一个返回 `Mschema` 对象本身的方法（它内部本来就有这个对象，`SchemaProvider` 只是把它格式化成了文本），Controller 直接把 `Mschema` 序列化成 JSON。

**不要**让前端去解析那段给模型看的文本——那段文本的格式是为模型优化的，随时可能调整，前端跟着它走会很脆弱。

```java
@GetMapping("/api/analytics/schema")
public Mschema schema() {
    return mschemaCacheService.get();   // 直接返回，Jackson 序列化 record
}
```

### 2.3 权限

这两个接口**要求登录**（Phase 2A 的全局拦截器默认就要求，不用额外做），但**不做数据范围过滤**——Schema 和术语是元数据，不是业务数据。所有登录用户看到的是同一份。

> 但要确认一件事：`Mschema` 里的**示例值**是从真实数据采样来的。Ticket 7 已经保证敏感字段不被采样，所以这里没有泄漏风险。**在 PR 描述里显式确认这一条**，不要默认它成立。

### 2.4 分析能力未启用时

`@ConditionalOnProperty` 会让相关 Bean 不存在。Controller 也加同样的条件注解，接口整体不注册 → 前端拿到 404。前端据此显示"分析能力未启用"（第 4.4 节）。

### 2.5 后端新增文件

| 文件 | 职责 |
|---|---|
| `capability/analytics/controller/AnalyticsMetadataController.java` | 两个 `@GetMapping`，薄封装，无业务逻辑 |
| `capability/analytics/schema/MschemaCacheService.java` | **修改**：加一个公开方法返回 `Mschema` 对象（如果 Ticket 7 已经有了就不用加） |
| `capability/analytics/glossary/GlossaryCatalog.java` | **修改**：加 `List<GlossaryEntry> allEntries()`（Ticket 8 已有 `allTerms()` 返回名字，这里要返回完整条目） |

## 3. 前端部分：前置事实

| 文件 | 现状 |
|---|---|
| `src/router.ts` | 路由表 + `beforeEach` 守卫，`/admin` 用了 `meta: { requiresAdmin: true }` 的分组模式 |
| `src/admin/layouts/AdminLayout.vue` | 管理区的布局壳，**这一票的两个页面可以参照它建一个 `AnalyticsLayout`**，也可以直接挂平级路由——看第 4.1 节 |
| `src/admin/api/sys-api.ts` | API 封装的写法参考（用 `http.ts` 的 `request`） |
| `src/admin/views/RoleListView.vue` | 只读列表页的写法参考，**照着它的结构和样式来** |
| `src/App.vue` | 侧栏导航，新页面的入口加在这里 |

## 4. 前端实现

### 4.1 路由

数据分析的元数据浏览**不需要 admin 权限**（普通分析用户就该能看），所以**不要**挂进 `/admin` 分组。

```ts
{ path: '/analytics/schema', component: SchemaBrowserView },
{ path: '/analytics/glossary', component: GlossaryView }
```

两个页面共用一个简单的 tab 切换即可，不需要专门的 Layout 组件（只有两个页面，一个 Layout 是过度设计）。在 `App.vue` 侧栏加一个"数据字典"入口指向 `/analytics/schema`。

### 4.2 `SchemaBrowserView.vue`

```
┌ 数据字典 ─────────────────────────────┐
│ [表结构] [业务术语]        [搜索框...] │
├───────────────────────────────────────┤
│ ▼ rental  租赁记录                     │  ← 默认折叠，点击展开
│    rental_id     SMALLINT   主键       │
│    rental_date   DATETIME   租出时间   │
│    user_id       BIGINT     经手业务员 │
│    ...                                 │
│    外键: inventory_id → inventory      │
│ ▶ payment  付款记录                    │
│ ▶ customer  客户                       │
└───────────────────────────────────────┘
```

要点：
- **默认全部折叠**，只显示表名 + 表注释。几十张表全展开会是一面字墙。
- 搜索框**同时匹配表名、表注释、字段名、字段注释**——用户经常记得"有个字段叫金额"但不记得在哪张表。命中时自动展开对应的表并高亮命中项。
- 示例值展示成小标签（`Examples: A, B, C`），没有示例值时整块不显示（不要显示"Examples: 无"）。
- 每张表标题右侧放一个"问一下这张表"按钮 → 跳 `/chat?mode=analytics&q=<模板问题>`，见 4.5。

### 4.3 `GlossaryView.vue`

比 Schema 页简单得多，一个列表即可：

```
活跃客户  [同义词: 活跃用户, active customer]
  近 30 天内至少有 1 笔租赁记录的去重客户。
  注意：这里的"活跃"是行为频次口径，和"高价值客户"（消费金额口径）不是一个概念…
  ┌ 参考 SQL ─────────────────────────┐
  │ SELECT COUNT(DISTINCT r.customer_id) …│  ← 等宽字体 + 复制按钮
  └───────────────────────────────────┘
```

- `description` 是多行文本（YAML 里的 `|` 块），渲染时**保留换行**（`white-space: pre-wrap`）
- 时间锚点那条元规则单独置顶展示，加一个视觉区分（它不是普通术语，是全局前提）
- `sqlFragment` 用等宽字体 + 复制按钮（复用 F4 的复制交互，如果 F4 已经做了就抽个共用的小组件；F4 还没做就在这里先实现，F4 复用）

### 4.4 分析能力未启用

两个接口 404 时，页面显示：

```
数据分析能力未启用
管理员需要先配置分析数据库（agenttrail.analytics.datasource）后才能使用。
```

**不要**显示成一个红色报错——这是一个正常的未配置状态，不是故障。

### 4.5 从表结构页跳到对话页

按钮跳 `/chat` 并带 query 参数。`ChatView` 需要读这两个参数：`mode` 预选"数据分析"模式，`q` 填进输入框（**不自动发送**——让用户看一眼、改一改再发，自动发送会让人措手不及）。

这需要 `ChatView` 加几行读 `route.query` 的逻辑。**如果 F4 还没做完，这部分先跳过**（按钮先不实现），等 F4 合入后再补——不要为了这个小功能去改一个正在被另一张票改动的文件，会冲突。在这一票的验收里把这条标成可选。

### 4.6 前端新增文件

| 文件 | 职责 |
|---|---|
| `src/api/analytics-api.ts` | 两个 GET 封装 + TS 类型（`MschemaDto`/`GlossaryEntryDto`），用 `http.ts` 的 `request` |
| `src/views/SchemaBrowserView.vue` | 表结构浏览 |
| `src/views/GlossaryView.vue` | 术语字典 |
| `src/components/CopyButton.vue`（新增或复用 F4 的） | 复制到剪贴板 + "已复制"反馈 |
| `src/router.ts` | 两条路由 |
| `src/App.vue` | 侧栏入口 |
| 对应的 `.spec.ts` | 参照 `RoleListView` 的测试写法 |

## 5. 验收标准

**后端**
- [ ] `GET /api/analytics/schema` 返回结构化 JSON（不是给模型看的那段文本），含表/字段/注释/示例值/外键
- [ ] `GET /api/analytics/glossary` 返回全部术语条目，含 `sqlFragment`
- [ ] 两个接口未登录访问返回 401
- [ ] `agenttrail.analytics.datasource.enabled=false` 时两个接口返回 404（Bean 不注册），不是 500
- [ ] PR 描述里确认了"示例值里不含敏感字段"（依据 Ticket 7 的采样跳过逻辑）

**前端**
- [ ] `/analytics/schema` 展示表列表，默认全部折叠
- [ ] 点击表名展开字段详情
- [ ] 搜索框能匹配表名/表注释/字段名/字段注释，命中时自动展开
- [ ] `/analytics/glossary` 展示全部术语，`description` 的换行被保留
- [ ] 时间锚点元规则置顶且视觉上和普通术语有区分
- [ ] `sqlFragment` 有复制按钮，复制后有可见反馈
- [ ] 分析能力未启用时显示说明文案而不是报错
- [ ] 侧栏有入口，普通（非 admin）账号也能访问
- [ ] `npm run test` 全绿

## 6. 实现顺序

1. 后端两个接口 + `MschemaCacheService`/`GlossaryCatalog` 的方法补充 + Controller 测试
2. `src/api/analytics-api.ts` + TS 类型（照着后端真实返回的 JSON 写类型，**先跑一次后端把响应体复制下来**，不要凭 Java 类推断）
3. `GlossaryView.vue`（更简单，先做）+ 测试
4. `SchemaBrowserView.vue` 的折叠列表 + 测试
5. 搜索/高亮
6. 路由 + 侧栏入口
7. 4.5 的跳转按钮（F4 已合入才做）

## 7. 明确禁止事项

- **不要**让前端解析 `SchemaProvider` 给模型看的那段文本，必须走结构化 JSON 接口
- **不要**做任何编辑能力（Schema 是自省出来的、术语是 git 管理的）
- **不要**把这两个页面挂进 `/admin` 权限分组
- **不要**默认展开所有表
- **不要**在分析能力未启用时显示成错误状态
- **不要**引入 UI 组件库
- **不要**让"问一下这张表"按钮自动发送消息
- **不要**在 F4 未合入时去改 `ChatView.vue`（避免冲突，见 4.5）
- 其余共享约束同 F1-F3（不引入 axios、不改现有业务 API 文件）

## 8. 和现有代码的边界

**后端新增**：一个 Controller；**后端修改**：Ticket 7/8 的两个类各加一个公开方法。
**前端新增**：一个 api 文件、两个 view、一个小组件、对应测试。
**前端修改**：`router.ts`（两条路由）、`App.vue`（一个侧栏入口）。
**不碰**：`ChatView.vue`（除非 F4 已合入且要做 4.5）、`admin/**`、`http.ts`。
