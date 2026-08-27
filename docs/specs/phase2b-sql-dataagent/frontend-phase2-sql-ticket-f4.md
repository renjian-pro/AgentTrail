# Ticket F4／18：数据分析对话模式 + SQL 过程可视化 — 技术开发文档

> GitHub issue: [#60](https://github.com/renjian-pro/AgentTrail/issues/60)

> 派生自 [`frontend-phase2-sql-dataagent.md`](frontend-phase2-sql-dataagent.md)。`Blocked by` 后端 [Ticket 12](backend-phase2-sql-ticket-12.md)（需要 `mode=analytics` 能真的路由到 DataAgent）。**不依赖** [F5](frontend-phase2-sql-ticket-f5.md)。

## 0. 范围边界

**这一票只做**：ChatView 加一个"数据分析"模式 + 数据分析工具调用的结构化渲染。

**这一票不做**：Schema 浏览页、术语字典页（F5）、任何新的后端接口。

## 1. 前置事实（先读这些文件，不要凭空设计）

| 文件 | 现状（必须先看一眼） |
|---|---|
| `src/views/ChatView.vue:20` | `type Mode = 'research' \| 'ppt' \| undefined`，`pendingMode` ref + `toggleMode(mode)` 切换 |
| `src/views/ChatView.vue:44-46` | `send()` 开头按 `pendingMode` 分流：research/ppt 走各自的同步 API，其余走 `streamChat` |
| `src/views/ChatView.vue:58-67` | SSE 消费循环，`chat.applyStreamEvent(event, assistant, message)` |
| `src/views/ChatView.vue` 模板 | `<CollapsibleChip v-for="tool in message.tools" :label="tool.name" :content="tool.detail" />` ← **这是这一票要分流的地方** |
| `src/api/chat-api.ts:5` | `ChatRequest = { message, conversationId?, modelId?, webSearchEnabled }` ← 要加 `mode?` |
| `src/stores/chat.ts` | `applyStreamEvent` 处理 `ToolStart`/`ToolEnd`，往 `assistant.tools` 里塞 `{ toolCallId, name, detail }` |
| `src/types/stream-event.ts` | `ToolStart = { toolName, toolCallId, arguments }`、`ToolEnd = { toolName, toolCallId, result }` |
| `src/components/CollapsibleChip.vue` | 只有 12 行：一个按钮 + 折叠的 `<pre>` |

**关键**：数据分析走的是**同一条 SSE 路径**（`streamChat`），不像 research/ppt 是独立同步接口。所以 `send()` 里**不需要**加第三个分支去调别的 API，只需要把 `mode` 传进请求体。

## 2. 验收标准

- [ ] 对话框下方出现"数据分析"模式按钮，和现有的 Deep Research／PPT 按钮同一排、同样的选中/取消交互
- [ ] 选中"数据分析"后发送，请求体里带 `mode: 'analytics'`
- [ ] 未选中时请求体的 `mode` 是 `undefined`（**不是空字符串**），且现有普通对话/research/ppt 行为完全不变（回归）
- [ ] `execute_sql` 的工具调用渲染成结构化卡片：SQL 段 + 结果表格 + 状态行，不是折叠的纯文本
- [ ] SQL 段做了换行格式化（`SELECT`/`FROM`/`WHERE`/`JOIN`/`GROUP BY`/`ORDER BY`/`LIMIT` 前换行），等宽字体展示
- [ ] SQL 段有"复制"按钮，点击后有可见反馈（"已复制"，2 秒后消失）
- [ ] 结果表格正确渲染表头和数据行
- [ ] 结果被截断时显示提示条（"共 156 行，展示前 20 行"）
- [ ] 被脱敏的值（`********`）在表格里有视觉标记（比如灰色 + 一个 🔒 或 tooltip 说明"该字段已脱敏"）
- [ ] 空结果显示专门的空状态，不是一张空表格
- [ ] 执行失败时显示失败原因文本，不显示空表格
- [ ] 结果文本无法解析成表格时**降级成原样文本展示**，不白屏、不报错
- [ ] `list_tables`/`describe_tables`/`lookup_glossary`/`validate_sql`/`calculate` 的调用有可读的步骤标签（不要求结构化渲染，但 label 要是中文人话，不是裸工具名）
- [ ] 分析能力未启用时（后端返回 4xx），显示明确说明而不是通用报错
- [ ] `npm run test` 全绿，新增组件有测试（参照 `ResearchReportCard.spec.ts` 的风格）
- [ ] 现有测试（`ChatView.spec.ts`/`chat.spec.ts`/`interaction.spec.ts`）全部仍然通过

## 3. 新增/修改文件清单

| 文件 | 改动 |
|---|---|
| `src/api/chat-api.ts` | `ChatRequest` 加 `mode?: 'analytics'`；`streamChat` 原样透传，**不改函数签名** |
| `src/views/ChatView.vue` | `Mode` 加 `'analytics'`；模板加一个模式按钮；工具渲染处按工具名分流；`send()` 里把 mode 传进请求体 |
| `src/components/SqlToolCard.vue`（新增） | `execute_sql`/`validate_sql` 的结构化渲染 |
| `src/components/SqlResultTable.vue`（新增） | 表格渲染 + 脱敏标记 + 截断提示 + 空状态 |
| `src/utils/sqlResult.ts`（新增） | 纯函数：解析工具返回文本 → 结构化对象；SQL 格式化 |
| `src/utils/sqlResult.spec.ts`（新增） | 上面那些纯函数的单测 |
| `src/components/SqlToolCard.spec.ts`（新增） | 组件测试 |

## 4. `ChatView.vue` 的三处改动（精确，不要重写这个文件）

### 4.1 Mode 类型和按钮

```ts
// 第 20 行附近
type Mode = 'research' | 'ppt' | 'analytics' | undefined

// modeLabel computed 加一个分支
const modeLabel = computed(() =>
  pendingMode.value === 'research' ? 'Deep Research'
  : pendingMode.value === 'ppt' ? 'PPT 生成'
  : pendingMode.value === 'analytics' ? '数据分析' : '')
```

模板里沿用现有 research/ppt 按钮的写法加第三个，`@click="toggleMode('analytics')"`。**不要**新写一套按钮组样式。

### 4.2 `send()` 不加新分支，只传 mode

```ts
async function send(message: string) {
  if (pendingMode.value === 'research') return runResearch(message)
  if (pendingMode.value === 'ppt') return runPpt(message)
  // analytics 不需要独立分支——它和普通对话走同一条 SSE 路径，
  // 唯一区别是请求体多一个 mode 字段。加分支反而会把这条路径复制一遍
  const mode = pendingMode.value === 'analytics' ? 'analytics' : undefined
  ...
  for await (const event of streamChat({
    message,
    conversationId: conversationId.value,
    modelId: modelId.value,
    webSearchEnabled: webSearch.value,
    mode                                   // ← 唯一的新增
  }, aborter.signal)) { ... }
}
```

**注意**：走到这里之后 `pendingMode` 要清掉（research/ppt 在 `runCapability` 里清的），否则下一条普通消息会被误当成分析请求。确认现有代码在这条路径上有没有清——没有的话在 `finally` 里补上。

### 4.3 工具渲染分流

```vue
<!-- 原来这一行： -->
<!-- <CollapsibleChip v-for="tool in message.tools" :key="tool.toolCallId" :label="tool.name" :content="tool.detail" /> -->

<!-- 改成： -->
<template v-for="tool in message.tools" :key="tool.toolCallId">
  <SqlToolCard v-if="isSqlTool(tool.name)" :tool="tool" />
  <CollapsibleChip v-else :label="toolLabel(tool.name)" :content="tool.detail" />
</template>
```

```ts
const SQL_TOOLS = new Set(['execute_sql', 'validate_sql'])
const isSqlTool = (name: string) => SQL_TOOLS.has(name)

// 其余工具只换个人话标签，渲染方式不变
const TOOL_LABELS: Record<string, string> = {
  list_tables: '查看数据表',
  describe_tables: '展开表结构',
  lookup_glossary: '查询业务术语',
  calculate: '计算',
  tool_search: '查找可用工具'
}
const toolLabel = (name: string) => TOOL_LABELS[name] ?? name
```

**不要修改 `CollapsibleChip.vue`**——它服务所有其它功能（文件问答、图表、联网搜索），改它会波及那些场景。

## 5. `src/utils/sqlResult.ts`（这一票的核心逻辑，纯函数，先写测试）

### 5.1 要解析什么

后端 `SqlResultFormatter`（后端 Ticket 11 第 7 节）返回三种形态的文本：

**成功**
```
查询成功，共 156 行（耗时 234ms）。以下是前 20 行：

| customer_id | name | total |
|---|---|---|
| 1 | MARY SMITH | 118.68 |

（结果超过 20 行，只展示前 20 行。不要从这 20 行里手工计算…）
```

**空结果**
```
查询执行成功，但没有匹配任何数据（0 行）。

空结果通常意味着以下之一：
① 过滤条件太严格 —— …
```

**失败**
```
查询失败：…
```

### 5.2 目标结构

```ts
export type SqlToolResult =
  | { kind: 'table'; columns: string[]; rows: string[][]; totalRows: number | null; truncated: boolean; elapsedMs: number | null; note: string }
  | { kind: 'empty'; guidance: string }
  | { kind: 'error'; message: string }
  | { kind: 'raw'; text: string }        // 解析不出来时的降级，必须有
```

### 5.3 解析实现要点

- **用宽松的正则，不要写严格的语法解析器**。后端文案未来可能微调，一个过于严格的解析器会在文案改一个字时全线崩溃。抓不到就返回 `{ kind: 'raw' }`，界面降级成纯文本——这比报错好得多。
- Markdown 表格解析：找连续的以 `|` 开头的行，第一行是表头，第二行是 `|---|` 分隔行（跳过），其余是数据行。按 `|` split 后 trim，去掉首尾空元素。
- 行数：从"共 N 行"里抓；抓不到就 `null`（不要瞎猜）
- `truncated`：文本里出现"只展示前"或表格行数 < 总行数
- 表格单元格里的 `|` 转义问题：后端不太可能产生，但解析时**每行的列数和表头对不上就整体降级成 raw**，不要产出一个错位的表格

### 5.4 SQL 格式化

```ts
/**
 * 极简格式化：在主要子句关键字前换行。不做完整 SQL 解析——
 * 目的只是让一条挤在一行的长 SQL 可读，不是做一个 SQL 美化器。
 * 关键字在字符串字面量里被误伤是可以接受的（只影响显示，不影响执行）。
 */
export function formatSql(sql: string): string {
  return sql.replace(/\s+/g, ' ').trim()
    .replace(/\s+(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|UNION(?: ALL)?|(?:LEFT |RIGHT |INNER |FULL )?JOIN)\s+/gi,
             '\n$1 ')
}
```

### 5.5 从 `ToolStart.arguments` 里取 SQL

`tool.detail` 目前是 store 拼出来的一段文本（去 `src/stores/chat.ts` 的 `applyStreamEvent` 确认它到底存了什么——**先读代码再动手**）。`SqlToolCard` 需要分别拿到"入参 SQL"和"返回结果"两部分。

如果现有 store 把 arguments 和 result 拼成了一个字符串，有两个选择：
- ① 在 store 里把 `tools` 项从 `{ toolCallId, name, detail }` 扩成 `{ toolCallId, name, detail, args?, result? }`（加字段不改原有字段，不破坏现有渲染）
- ② 在 `SqlToolCard` 里二次拆分 `detail`

**选 ①**——二次拆分是在给自己挖一个未来的坑。加字段是向后兼容的，`CollapsibleChip` 继续用 `detail` 不受影响。这个改动要同步更新 `chat.spec.ts`。

## 6. `SqlToolCard.vue` 结构

```
┌─ 🔍 执行查询 ─────────────────── [复制] ─┐
│ SELECT c.customer_id, c.first_name       │  ← 等宽字体，formatSql 换行后的
│ FROM customer c                          │
│ WHERE c.active = 1                       │
│ LIMIT 200                                │
├──────────────────────────────────────────┤
│ 共 156 行 · 234ms · 展示前 20 行          │  ← 状态行
│ ┌──────┬─────────┬────────┐              │
│ │ id   │ name    │ total  │              │  ← SqlResultTable
│ ├──────┼─────────┼────────┤              │
│ │ 1    │ MARY    │ 118.68 │              │
│ └──────┴─────────┴────────┘              │
└──────────────────────────────────────────┘
```

- 默认**展开**（不像 `CollapsibleChip` 默认折叠）——SQL 和结果是数据分析的核心证据，藏起来就失去了"过程可见"的意义。给一个折叠按钮让用户自己收起来。
- `validate_sql` 只渲染 SQL 段 + 校验结论，没有结果表格。

## 7. `SqlResultTable.vue`

- 表格宽度超出时**横向滚动**（`overflow-x: auto`），不要挤压列宽
- 脱敏值：值等于 `********` 时加一个 class，样式上灰掉 + 一个 🔒 图标 + `title="该字段已脱敏"`
- 数值列右对齐（判断：整列的值都能 `Number()` 转成非 NaN），文本列左对齐
- 行数多时表格自身**不做分页**——后端已经只返回 20 行了，前端再分页是多余的
- 空结果走 `kind: 'empty'` 分支，渲染引导文案，不渲染表格

## 8. 分析能力未启用的处理

后端 Ticket 12 第 7.3 节定义了：分析库没配时返回 4xx + 明确消息。前端在 `send()` 的 catch 里已经有 `toErrorMessage(failure)`，确认这条消息能透传到 `error.value` 并展示。如果后端的错误体结构和现有 `toErrorMessage` 的假设不一致，**去 `src/api/http.ts` 确认 `toErrorMessage` 怎么解析的**，必要时补一个分支——但不要重写它。

## 9. 实现顺序

1. `src/utils/sqlResult.ts` 的解析和格式化函数 + 单测（**先做这个**，纯函数，不需要任何组件，可以把后端三种返回形态的真实文本当成测试 fixture）
2. `SqlResultTable.vue` + 组件测试（喂一个解析好的对象，断言渲染）
3. `SqlToolCard.vue` + 组件测试
4. store 的 `tools` 项加 `args`/`result` 字段（第 5.5 节）+ 更新 `chat.spec.ts`
5. `chat-api.ts` 的 `mode` 字段
6. `ChatView.vue` 三处改动 + 回归跑一遍现有测试
7. 手工端到端验证：起后端，用 `analyst_test` 账号问一个真实问题，确认整条链路的渲染

第 1 步的 fixture **必须用后端真实返回的文本**，不要自己编——编出来的格式和后端对不上，测试全绿但线上白屏。跑一次后端把真实输出复制下来。

## 10. 明确禁止事项

- **不要**新建 `AnalyticsView.vue` 或另一套 SSE 消费循环
- **不要**修改 `CollapsibleChip.vue`
- **不要**修改 `streamChat` 的函数签名（只给 `ChatRequest` 加可选字段）
- **不要**引入 UI 组件库、SQL 高亮库、Markdown 表格解析库（`marked` 之类）——解析逻辑就几十行，一个库不值得
- **不要**在解析失败时抛错或白屏，必须降级成 `kind: 'raw'`
- **不要**做前端排序/筛选/导出 CSV（spec 的 Out of Scope）
- **不要**给用户提供手写 SQL 的输入框
- **不要**改 `research`/`ppt` 两个模式的任何现有行为
- **不要**用自己编的返回文本当测试 fixture

## 11. 和现有代码的边界

**修改**：`ChatView.vue`（三处）、`chat-api.ts`（一个可选字段）、`stores/chat.ts`（`tools` 项加两个可选字段）、`chat.spec.ts`。
**新增**：两个组件 + 一个 utils + 三个测试文件。
**不碰**：`http.ts`（除非第 8 节确认确实需要）、`admin/**`、`auth` 相关任何文件、`file-api.ts`/`ppt-api.ts`/`research-api.ts`。
