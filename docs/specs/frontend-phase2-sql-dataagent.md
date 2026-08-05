# AgentTrail 前端 — Phase 2B：数据分析（DataAgent）需求 Spec

> 状态：草案。与 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 是同一能力的前后端两侧。
> 前提：Phase 2A 前端（[`frontend-phase2-auth.md`](frontend-phase2-auth.md)，Ticket F1-F3）已交付——登录态、`http.ts` 的 token 注入与 401 处理、路由守卫、`admin/` 管理后台都已存在。
> 拆票：[F4](frontend-phase2-sql-ticket-f4.md)（对话侧）、[F5](frontend-phase2-sql-ticket-f5.md)（Schema/术语浏览）。

## Problem Statement

后端 Phase 2B 交付后，DataAgent 的完整链路（Schema 探查 → 消歧 → SQL 校验 → 权限改写 → 只读执行 → 脱敏 → 计算 → 图表）能跑通，但对用户来说是个黑盒：

1. **没有入口。** 现有 `ChatView` 的模式切换只有 Deep Research 和 PPT 两个，走不到 `mode=analytics`。
2. **过程不可见。** 现有 `CollapsibleChip` 把所有工具调用一律渲染成"折叠起来的一段纯文本"。数据分析的关键中间产物——**它到底跑了哪条 SQL、结果是什么、哪些字段被脱敏了**——全被压成一坨 JSON 字符串塞在折叠块里。用户无法判断这个数字是怎么来的，也就无法信任它。
3. **不知道能问什么。** 用户不知道库里有哪些表、"活跃客户"这类词系统认不认，只能靠试。

第 2 点是这一期前端的核心：**数据分析的可信度来自过程可见，不是结果好看。** 一个能看到"它查了 rental 表、跑了这条 SQL、返回 156 行"的用户，才会相信那个数字。

## User Stories

### 对话侧（F4）

1. 作为用户，我想在对话框上切到"数据分析"模式，用自然语言问业务数据问题
2. 作为用户，我想看到 Agent 执行的每一条 SQL（格式化后的、可读的），而不是一段挤在一行的字符串
3. 作为用户，我想看到查询结果以表格形式呈现，而不是原始 Markdown 文本
4. 作为用户，我想一眼看出结果是否被截断（"共 156 行，展示前 20 行"）
5. 作为用户，我想看出哪些字段被脱敏了，而不是以为数据库里真的存着一串星号
6. 作为用户，我想看到 Agent 的思考步骤（探索 Schema → 查术语 → 生成 SQL → 执行），而不是等一分钟后突然蹦出一个数字
7. 作为用户，我想在查询失败时看到人话的失败原因（"这条查询会扫描太多数据"），而不是一段 Java 异常
8. 作为用户，我想复制某条 SQL 拿去别处用
9. 作为用户，我想在分析能力未启用时看到明确说明，而不是一个报错

### 浏览侧（F5）

10. 作为用户，我想浏览分析库里有哪些表、每张表有哪些字段和业务含义，以便知道能问什么
11. 作为用户，我想查看系统已登记的业务术语和它们的口径定义，以便用对词
12. 作为用户，我想从表结构页直接跳到对话页并带上一个针对该表的问题模板

## Implementation Decisions

### 复用 ChatView，不新建一套对话界面

`ChatView` 已有完整的 SSE 消费循环、模式切换、停止、错误处理、会话管理。数据分析走的是**同一个 `POST /agent/v1/chat` SSE 接口**（后端 Ticket 12 只是加了个 `mode` 字段），不像 Deep Research/PPT 那样是独立的同步接口——所以它比那两个更容易接：只要多一个模式按钮 + 请求体多一个字段。

新建一个 `AnalyticsView` 会导致 SSE 消费逻辑、`applyStreamEvent`、停止逻辑全部重复一遍，两边随时会漂移。**明确不这么做。**

### 工具调用的差异化渲染，不改 CollapsibleChip

`CollapsibleChip` 现在是所有工具的统一渲染方式（`ChatView.vue` 模板里 `v-for="tool in message.tools"`）。数据分析工具需要结构化渲染，但**不要去改 `CollapsibleChip`**——它服务的是"通用工具调用"这个场景，改它会影响文件问答、图表、联网搜索所有现有功能。

做法：在渲染层按工具名分流，`execute_sql`/`validate_sql` 用新组件，其余照旧走 `CollapsibleChip`。

### 结果表格从工具返回文本里解析，不新增接口

后端 `SqlResultFormatter`（Ticket 11 第 7 节）返回的是 Markdown 表格 + 说明文字。前端**解析这段 Markdown** 拿到表头和行，不要为了拿结构化数据再加一个 REST 接口——那会让同一份数据有两个来源，且模型看到的和用户看到的可能不一致。

解析失败时降级成原样展示文本（`CollapsibleChip` 的行为），不能白屏。

### 不引入 UI 组件库、不引入 SQL 高亮库

延续 Phase 2A 的约束（`frontend-phase2-auth.md` 已定）：纯手写组件 + `src/styles.css`。SQL 的可读性靠**格式化换行 + 等宽字体**解决，不引入 highlight.js/prismjs——一个只在这一个场景用到的高亮库不值得进 bundle。

## Out of Scope

- SQL 编辑器 / 让用户手写 SQL 执行——DataAgent 的入口是自然语言，开一个 SQL 输入框等于绕过整套安全和权限机制
- 结果集的前端排序/筛选/导出 CSV——用户要什么让 Agent 重新查，前端不做二次数据加工
- 图表的前端渲染——后端返回的是图片 URL（Phase 5 的 mcp-echarts + 对象存储），前端只负责 `<img>`
- Schema 的编辑能力（F5 是只读浏览）
- 术语字典的在线编辑——它是 git 管理的 YAML（后端 Ticket 8），改它要走 code review
