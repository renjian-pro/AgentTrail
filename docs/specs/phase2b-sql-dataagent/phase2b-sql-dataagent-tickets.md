# Phase 2B（SQL 数据分析能力包）拆票总览

> 这是 Phase 2B 全部 10 张票的索引和依赖图。**开工前先读这一页**，再去读要做的那张票。
>
> 需求 Spec：[后端](backend-phase2-sql-dataagent.md) ｜ [前端](frontend-phase2-sql-dataagent.md)
> 架构决策：[ADR-0003](../../adr/0003-agentscope-isolated-data-agent-runtime.md)
> 踩坑清单：[engineering-pitfalls-and-highlights.md](../../engineering-pitfalls-and-highlights.md) 第六节（#21-#29）、第八节（#35-#37）

## 现状

**Phase 2A（认证 / RBAC / 数据范围解析）已全部交付**，代码在：

- `com.agenttrail.auth.*`（Sa-Token 登录、全局拦截、`SysStpInterface`）
- `com.agenttrail.sys.*`（五表 RBAC + `sys_permission` 权限点、Store/Service/Controller）
- `com.agenttrail.sys.datascope.DataScopeResolver`（给 `userId` 返回可见部门列表，含 fail-closed）
- `AgentLoopController.java:54-56` 已经把真实 `userId` 塞进 `RunnableParams.toolParams`
- 前端 `admin/**`、`stores/auth.ts`、路由守卫

**Phase 2B 要做的是 SQL 分析工具本身**，它**消费** Phase 2A 的产出，不重新设计权限模型。

## 十张票

全部已发布为 GitHub issue（`ready-for-agent` 标签），本地技术文档是每张票的详细版。

| # | issue | 票 | 做什么 | 依赖 | 规模 |
|---|---|---|---|---|---|
| 06 | [#52](https://github.com/renjian-pro/AgentTrail/issues/52) | [导入业务数据 + 只读数据源](backend-phase2-sql-ticket-06.md) | sakila 导进**现有 `agenttrail` 库** + 改造 + 归属回填 + **按表授权的只读账号** + 独立连接池 | — | 中 |
| 07 | [#53](https://github.com/renjian-pro/AgentTrail/issues/53) | [M-Schema + Schema 工具](backend-phase2-sql-ticket-07.md) | JDBC 自省 + Redis 缓存（含多实例刷新锁）+ `list_tables`/`describe_tables` | #52 | 大 |
| 08 | [#54](https://github.com/renjian-pro/AgentTrail/issues/54) | [业务术语字典](backend-phase2-sql-ticket-08.md) | YAML glossary + 时间锚点 + `lookup_glossary` | #53 | 小 |
| 09 | [#55](https://github.com/renjian-pro/AgentTrail/issues/55) | [SQL 安全校验](backend-phase2-sql-ticket-09.md) | JSqlParser AST 校验 + `validate_sql` | #53 | 大 |
| 10 | [#56](https://github.com/renjian-pro/AgentTrail/issues/56) | [数据权限 AST 改写](backend-phase2-sql-ticket-10.md) | `DataScopeRewriter` + `PermissionRule` 路由 | #52, #55 | 大 |
| 11 | [#57](https://github.com/renjian-pro/AgentTrail/issues/57) | [execute_sql 流水线](backend-phase2-sql-ticket-11.md) | EXPLAIN 预检 + 只读执行 + 脱敏 + 格式化 + `execute_sql` | #52, #55, #56 | 大 |
| 12 | [#58](https://github.com/renjian-pro/AgentTrail/issues/58) | [calculate + 装配接线](backend-phase2-sql-ticket-12.md) | `calculate` + SKILL.md + 延迟工具注册 + HTTP 入口 | #53, #54, #55, #57 | 中 |
| 13 | [#59](https://github.com/renjian-pro/AgentTrail/issues/59) | [Golden Tasks 评测](backend-phase2-sql-ticket-13.md) | 评测集 + Runner + CI 门禁 | #58 | 中 |
| F4 | [#60](https://github.com/renjian-pro/AgentTrail/issues/60) | [数据分析对话模式](frontend-phase2-sql-ticket-f4.md) | ChatView 加模式 + SQL 过程可视化 | #58 | 中 |
| F5 | [#61](https://github.com/renjian-pro/AgentTrail/issues/61) | [Schema/术语浏览页](frontend-phase2-sql-ticket-f5.md) | 两个只读接口 + 两个只读页面（全栈票） | #53, #54 | 中 |

## 依赖图

```
06 分析库基建
 ├─ 07 M-Schema ──┬─ 08 术语字典 ─────────┐
 │                ├─ 09 SQL 安全校验 ──┬──┤
 │                └────────────────────┼──┼─ F5 浏览页（也依赖 08）
 ├─ 10 权限改写（也依赖 09）───────────┤  │
 └─ 11 execute_sql（也依赖 09、10）────┴──┤
                                          └─ 12 装配接线
                                               ├─ 13 Golden Tasks
                                               └─ F4 对话模式
```

**可并行的组合**：08 / 09 / 10 三张票在 07 完成后互不阻塞（10 需要 09 的 JSqlParser 版本结论，但不需要 09 的代码）；F5 只依赖 07/08，可以和 09/10/11 并行。

**关键路径**：`06 → 07 → 09 → 10 → 11 → 12`，其余都挂在这条链的旁边。

## 建议实现顺序（单人串行时）

1. **06**（没它什么都跑不起来）
2. **07**（后面所有票都要在它建立的包结构里加东西，且它顺带做了 `JsonToolCallback` 提升 public 这个公共前置）
3. **09**（纯单测、不碰数据库，跑得快，先把 AST 手感练出来）
4. **10**（AST 操作的进阶，接着 09 做认知连贯）
5. **11**（把 09/10 组装起来，第一次跑通端到端）
6. **08**（小票，放这里当调剂；它和 11 无依赖，提前做也行）
7. **12**（收口，第一次能被用户真正调用）
8. **F4**（有了后端能跑，前端才有东西可对）
9. **13** 和 **F5**（收尾，互不影响）

## 全局约束（每张票都适用，不再各自重复）

### 技术栈

- **持久化一律手写 `JdbcClient`**（`org.springframework.jdbc.core.simple.JdbcClient`），参照 `loop/persistence/JdbcSessionStore.java`。**不要**引入 MyBatis/JPA/Hibernate。
- **工具一律用 `JsonToolCallback`**（Ticket 07 会把它提升为 public），参照 `loop/tools/FileContentTool.java`。**不要**用 `@Tool` 注解。
- **包边界**：`com.agenttrail.loop.*` 只装 Runtime 通用机制（不知道 SQL、不知道 PPT）；业务能力包走 `com.agenttrail.capability.analytics.*`。见 `CONTEXT.md`。
- **集成测试连真实 MySQL，禁用 H2**——SQL AST 校验和权限改写在 H2 上行为和 MySQL 不同，会掩盖真实 bug。Phase 2B **不用 Testcontainers**，直接连本机 MySQL 实例上的分析库（理由和测试基座见 [Ticket 06 第 8 节](backend-phase2-sql-ticket-06.md)）；连不上时测试用 `Assumptions` 跳过，不是失败。这些都是 `*IT`，走 Failsafe，CI 默认不跑。
- 新表的时间列用 `BIGINT` 存 epoch millis（对齐 `db/schema.sql` 里较新的那批表），不用 `TIMESTAMP`。

### `userId` / `dept_id` 是谁注入的（跨 4 张票，单独画一次）

**结论先行：全程服务端注入，LLM 一处都碰不到。** 实现时任何一步想"让模型自己传"都是错的。

先分清两个不同的东西：

| | 是什么 | 谁决定 | 什么时候 |
|---|---|---|---|
| `rental.user_id` / `rental.dept_id` **列里的值** | 静态业务数据（这笔单子当初谁经手、算哪个部门业绩） | 导入时的回填脚本 | 一次性，之后不变（Ticket 06 §4.3） |
| SQL 里的 **过滤条件** `WHERE dept_id IN (...)` | 运行时的权限边界 | 服务端 AST 改写 | 每次查询（Ticket 10） |

运行时链路：

```
HTTP 线程  StpUtil.getLoginIdAsString()        ← 认证框架，真实登录态
   ↓
RunnableParams.toolParams{userId}              ← 在原始线程冻结；切到 Reactor 调度器后
   ↓                                              ThreadLocal 就没了，所以必须在最外层固化
ToolParamInjector 按 inputSchema 白名单强制覆盖 ← 模型填的任何 userId 值在这一步被丢弃
   ↓                                              （Ticket 11 §5.1 还有一层兜底拒绝）
DataScopeResolver.resolve(userId)              ← 走主库查 sys_user_role/sys_user_dept/sys_dept
   ↓                                              分析账号读不到这些表，也不需要读
DataScopeContext(scope, deptIds)
   ↓
DataScopeRewriter 在 AST 上注入条件             ← 模型写的 SQL 在这一步被改写（Ticket 10）
   ↓
analytics_ro 账号执行                           ← 只能读授权过的业务表（Ticket 06 §5）
```

**LLM 唯一提供的是业务 SQL 主体**（`SELECT COUNT(*) FROM rental`），权限条件是改写上去的。

模型主动写权限条件也不构成风险：改写的条件是 `AND` 上去的，**`AND` 只能收窄不能放宽**。模型写 `WHERE dept_id = 999`（它看不到的部门），改写后是 `(dept_id = 999) AND (dept_id IN (3,4))`，结果为空。SKILL.md 里让它别写这类条件，是为了避免它困惑和浪费 token，**不是安全依赖**。

三层各自独立成立：模型填的 `userId` 被覆盖 → 条件由服务端注入 → 前两层全失效时只读账号仍然只能读那批业务表。

### 安全原则（这几条在多张票里反复出现，是同一套思路）

1. **Fail-closed**：任何"拿不准"的情况一律拒绝，不放行。解析异常、权限解析失败、部门列表为空——全部是拒绝，不是"当作没有限制"。
2. **不信任模型的自觉**：这是 ReAct 不是 Workflow，SKILL.md 写得再严格模型也可能不遵守。`execute_sql` 无条件重跑安全校验（不管有没有先调 `validate_sql`），DataAgent 执行器压根不挂 Bash（不只是提示词里禁止）。
3. **不信任模型填的系统参数**：`userId` 走 `RunnableParams.toolParams` 强制注入通道覆盖，工具内部还要再兜底校验一次。
4. **提示词是软约束，代码是硬边界**：两者都要有，但永远不要用前者代替后者。

### 披露规则

代码注释、commit message、文档里**不点名具体的参考实现来源仓库**。方法论表述为"研读了真实生产形态 DataAgent 实现后独立实现"。见 `AGENTS.md`。

## 每张票的文档结构（都一样，方便快速定位）

```
0. 范围边界        ← 先看这个，防止越界做了别人的活
1. 前置事实/技术假设验证  ← 有"先验证"标记的地方不许跳过
2. 验收标准        ← 机械可核对的 checkbox
3. 新增文件清单    ← 精确路径 + 每个类的唯一职责
4-8. 核心实现细节  ← 代码骨架、SQL、算法，含"为什么"
9. 实现顺序        ← 按这个顺序做，每步先写测试
10. 明确禁止事项   ← 常见跑偏点，逐条对照
11. 和现有代码的边界 ← 改什么、新增什么、绝对不碰什么
```

## 三个"参考材料里没有、需要从零设计"的点

交叉核对（[dodoagentx-crosscheck](../../dodoagentx-crosscheck-2026-08-03.md)）确认，以下三项参考实现自己也没做，Phase 2B 里是新增设计：

| 主题 | 落在哪张票 |
|---|---|
| DataAgent 评测体系（参考项目连 `src/test` 目录都没有） | 13 |
| SQL 分析场景的中断恢复语义 | 11（只读工具重放无副作用的边界声明）+ 复用 Phase 1 已有的 `PauseState` |
| DataAgent 的多节点部署 | 06（连接池按实例数核算）+ 07（M-Schema 刷新用 `RedisTaskLock` 抢锁） |

## 数据放在哪（2026-08-04 定）

业务分析数据**直接导进现有的 `agenttrail` 库**，不新建库、不新建容器，复用本机已在运行的 MySQL 实例。

这个决定带来一个必须正面处理的安全点：`agent_session`（所有用户的对话历史）、`agent_trace`（每轮 Prompt 和工具调用）、`sys_user.password` 现在和业务表同库。**只读账号只授权本期导进来的那批业务表**（sakila 基础表 + `user_profile` + `dim_dept`），一张 `sys_*`/`agent_*` 都不授予——详见 [Ticket 06 第 5 节](backend-phase2-sql-ticket-06.md)，那是整个 Phase 2B 的安全地基。

连带影响：因为分析账号读不到 `sys_user_dept`，权限改写（Ticket 10）不能用 `EXISTS` 子查询走中间表，所以 `user_profile` 冗余了一个 `dept_id` 列，`sys_dept` 也以 `dim_dept` 只读快照的形式导了一份进来。这两处冗余不是设计瑕疵，是授权边界的直接推论。

顺带一个架构收益：Phase 2A 的部门范围解析走 `ancestors LIKE '前缀,%'` 实时查询，**不存在**参考实现那种"内存邻接树在多节点下要不要挪 Redis"的问题——这是选型带来的连锁简化，不需要额外设计。
