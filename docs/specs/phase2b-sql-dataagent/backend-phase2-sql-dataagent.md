# AgentTrail 后端 — Phase 2B：SQL 数据分析能力包（DataAgent） 需求与技术方案

> 状态：草案，按 `to-spec` 模板整理，尚未发布为 GitHub issue（`ready-for-agent` 标签）。
> 前提：[`backend-phase2-auth.md`](../phase2a-auth/backend-phase2-auth.md)（Phase 2A）已交付 `sys_user`/`sys_role`/`sys_dept`/`sys_user_role`/`sys_user_dept` 五表 RBAC + `DataScopeResolver`（给定 `userId` 返回可见 `deptId` 列表），并把 `userId` 接进了 `RunnableParams.toolParams` 强制注入通道。这份 spec 是 Phase 2A 之后的下一步——SQL 分析工具本身：Schema、消歧、SQL 安全、执行、权限改写（消费 Phase 2A 的产出）、脱敏、计算、图表。
> 关联文档：[`roadmap.md`](../../roadmap.md) Phase 2 表格（机制清单的原始来源）、[`ADR-0003`](../../adr/0003-agentscope-isolated-data-agent-runtime.md)（AgentScope 仅作隔离 Runtime Adapter 的边界）、[`dataagent-design-audit-2026-08-03.md`](../../dataagent-design-audit-2026-08-03.md)（工程约束审计）、[`engineering-pitfalls-and-highlights.md`](../../engineering-pitfalls-and-highlights.md) 第六/八节（踩坑点 #21-#29、#35-#37，实现时逐条对照）。
> 证据规则：同其余 spec，只陈述 AgentTrail 的领域约束、架构决策、代码事实和测试结论；不记录私人素材、本机路径或历史项目名（见 ADR-0003）。
>
> **已拆票**：本 spec 的实现拆成 10 张票（后端 06-13、前端 F4-F5），依赖图和建议顺序见 [`phase2b-sql-dataagent-tickets.md`](phase2b-sql-dataagent-tickets.md)。每张票都有独立的技术开发文档，本 spec 只定"做什么和为什么"，"怎么做"在票里。

## 0. 这份文档解决什么问题

`roadmap.md` 的 Phase 2 表格和 `dataagent-design-audit-2026-08-03.md` 已经确定方向性决策，但仍停留在“机制名称 + 一句话说明”的粒度，没有下沉到类名、接口、表结构、算法和测试计划，不能直接拆票实现。这份 spec 将设计细化一级，并明确标注当前 roadmap 尚未覆盖、需要新增设计的部分（见第 1 节）。

## 1. Gap Analysis：当前能力与工程目标核对结果

### 1.1 已覆盖、可直接细化为实现方案的部分

roadmap.md Phase 2 已列出 M-Schema、SQL 安全校验、核心工具、业务消歧、复杂计算工具、完整数据权限模型和敏感字段脱敏。本文档第 5 节把这些方向落到可实现的接口、算法与测试；它们不是遗漏，而是需要补足工程细节。

### 1.2 真正的遗漏点：roadmap 尚未覆盖的三个方向

这三点来自对当前代码、现有测试和部署目标的逐项审计，不能靠套用通用样例解决，必须在 AgentTrail 内形成独立设计：

| 主题 | roadmap.md 现状 | 当前项目证据 | 结论 |
|---|---|---|---|
| **DataAgent 评测体系** | Phase 3 治理层有通用“Golden Set 50-200 条 + LLM-as-Judge”，未细化到 DataAgent | 现有测试没有覆盖 Golden SQL、权限泄漏、敏感字段泄漏和查询资源上限；Spider 2.0/BIRD 等公开 benchmark 也不验证本项目的权限边界 | 需要在本文档新增领域评测设计（见 5.10） |
| **中断恢复（SQL 分析场景特有语义）** | Phase 1 已有通用 `PauseState`/`SafePoint` 断点恢复机制 | DataAgent 采用 ReAct+Skill 而非固定 DAG，无需另建七阶段状态机；但必须明确只读工具是否可安全重放 | 复用通用 `PauseState`，新增只读工具恢复语义（见 5.11） |
| **多节点/多实例部署（DataAgent 专属）** | Phase 1 已有 Redis 分布式任务锁 + Pub/Sub 跨实例中断 | 部门范围解析使用 `ancestors LIKE '前缀,%'` 的实时查询，不依赖进程内部门树；但 M-Schema 缓存刷新和只读连接池容量仍受实例数影响 | 补齐 M-Schema 并发刷新保护与连接池容量核算（见 5.12） |

### 1.3 一个值得单独指出的架构优势

因为 Phase 2A 已经选择“部门范围用 `ancestors` 前缀查询而不是内存邻接表 BFS”，数据范围解析不存在“哪个实例的内存树是最新的”这类一致性问题：每次解析都是实时 SQL，不依赖进程内缓存。这是当前数据模型带来的直接简化。

---

## 2. Problem Statement

Phase 2A 交付后，AgentTrail 有了真实用户身份和"这个用户能看到哪些部门"的解析能力，但还完全没有 SQL 数据分析工具本身——ADR-0003 定的目标调用链（`Schema → glossary/探针 → validate SQL(AST) → rewrite data scope → read-only execute → mask result → calculate/chart Artifact → result verifier`）一个环节都还没写。没有这套工具，DataAgent 这个 Capability Pack 不存在；而这套工具如果做得不对，会直接导致三类真实风险：越权数据泄漏（权限改写不当）、敏感信息泄漏（脱敏被绕过）、生产库被写坏或拖垮（SQL 安全校验不到位、没有资源限制）。

## 3. Scope

本 spec 覆盖 SQL 数据分析工具链本身：Schema 探查、业务消歧、SQL 生成前后的安全校验、执行、数据权限改写（**消费**而非重新设计 Phase 2A 的 `DataScopeResolver`）、敏感字段脱敏、复杂计算、图表生成，以及三个新增的治理设计（评测、恢复语义、多节点）。不包含：Phase 2A 已完成的用户/角色/部门管理（见其 spec 的 Out of Scope 对齐）；Phase 3 的通用 Hooks/审计/成本治理框架（本 spec 只负责"DataAgent 要挂哪些 Hook 点"，不重新设计 Hooks 框架本身）。

## 4. User Stories

### Schema 与消歧

1. 作为 DataAgent，我想先看到一份精炼的表清单（不是原始 DDL），以便判断本次查询涉及哪些表，不被无关表的噪声干扰
2. 作为 DataAgent，我想展开某几张表的完整字段详情（类型、业务含义、示例值、主外键），以便准确生成 SQL
3. 作为 DataAgent，我想在拿不准"活跃客户""近30天"这类业务术语的确切口径时，查一份术语字典，得到精确定义和可复用的 SQL 片段，而不是自己猜
4. 作为 DataAgent，我想知道"当前时间"在这个历史快照型数据集里的正确定义，而不是直接用系统时钟，导致"最近N个月"这类查询永远查出空结果

### SQL 生成、校验与执行

5. 作为 DataAgent，我想在真正执行前预检验一条 SQL 是否安全（只读、无危险函数、无越权语句结构），并在不安全时拿到具体到某一条规则的失败原因，以便自己重写
6. 作为系统维护者，我想确保就算 DataAgent 跳过预检验直接执行，安全校验依然会在真正执行前被无条件重新跑一遍，不信任调用方
7. 作为系统维护者，我想在 SQL 语法看似安全但执行计划显示会全表扫描/JOIN 爆炸时提前拦截，而不是等它真的跑起来拖垮数据库
8. 作为已登录用户，我想我的查询结果自动按我的数据范围收窄（不需要自己在问题里声明"只看我部门的"），且这个过滤不能被我的提问内容影响或绕过
9. 作为已登录用户，我想我看不到自己数据范围之外的行，即使 SQL 用了 `LEFT JOIN`、子查询、`UNION`、别名等复杂写法
10. 作为已登录用户，我想密码、身份证、住址这类敏感字段永远显示为掩码，即使查询语句给它们起了别名
11. 作为 DataAgent，我想在查询报错、返回空结果、结果被截断这几种情况下，拿到能指导下一步动作的具体反馈，而不是一句"出错了"

### 计算与可视化

12. 作为 DataAgent，我想有一个独立的计算工具做环比、占比这类最终标量公式计算，而不是自己心算或把公式硬塞进 SQL
13. 作为 DataAgent，我想能生成图表，且图表不会把大量 base64 数据塞进对话上下文

### 治理（新增）

14. 作为系统维护者，我想有一套 Golden Tasks 回归集，覆盖 SQL 正确性、越权、敏感字段泄漏、空结果解释、可复现性，在改动 SQL 安全/权限改写逻辑后能自动跑一遍确认没有回归
15. 作为系统维护者，我想确认 DataAgent 多步分析任务中途中断（连接断开、实例重启）后能安全恢复，且恢复过程不会因为"重新执行同一个只读查询"产生任何副作用
16. 作为运维人员，我想确认 DataAgent 用到的所有缓存（M-Schema）在多实例部署下不会因为并发刷新而产生一致性问题，且 SQL 目标库的连接池容量是按实例数正确核算的，不是从单机配置直接复制

## 5. Implementation Decisions（技术方案）

包名统一放在 `com.agenttrail.capability.analytics`（domain 子包不泄漏 AgentScope 类型，遵循 ADR-0003）。

### 5.1 Schema Provider：M-Schema（默认）+ YAML（可选）双实现

- `SchemaProvider` 接口只有两个方法：`listTables()`、`describeTables(List<String> tableNames)`，工具代码只依赖接口，不关心底层实现，切换只改配置项 `data-agent.schema.provider: mschema|yaml`（默认 `mschema`）。
- **`MschemaProvider`**（默认实现）：
  - `MschemaIntrospector` 用 JDBC `DatabaseMetaData` 自省：读表清单（`REMARKS` 列即表注释，catalog 必须从当前 `Connection` 取，MySQL 不传 catalog 会把所有库的表都捞出来）→ 读列+主键（`getColumns`+`getPrimaryKeys`）→ 读外键（`getImportedKeys`）→ 异步采集字符串字段示例值（仅 `VARCHAR/CHAR/ENUM/SET/TEXT` 类列，`CompletableFuture.allOf(...).get(5, TimeUnit.SECONDS)` 超时保护，超时的列放弃采样但不阻塞整体自省；采样前先过滤敏感字段名单，不让敏感值进缓存——这一点和脱敏是两条独立防线，见 5.6）。
  - `MschemaFormatter` 把内省结果格式化成紧凑文本：`(字段名: 类型, 描述, 示例值)`，外键单独汇总成 `Foreign keys` 块；示例值清洗规则——日期时间只留一个、长文本超 50 字符丢弃/超 20 字符只留一个、邮箱和 URL 直接过滤。
  - `MschemaCacheService`：Redis 缓存内省结果（`Mschema` record 用 Jackson 序列化，`TableDef` 字段用 `LinkedHashMap` 保证顺序），三层兜底读取——① 优先读 Redis；② Redis 不可用降级到本地内存副本；③ 都没有则同步 `refresh()`。定时任务每天固定时间刷新（如果确认 schema 稳定可关闭）。多实例并发刷新保护见 5.12。
  - 排除系统表：`exclude-tables` 配置支持通配符（如 `agent_*`），不把 AgentTrail 自己的会话/追踪/记忆表暴露给数据分析。
- **`YamlSchemaProvider`**（可选实现）：从 classpath 静态 YAML 读取，模型 `Root(tables, views, glossary)` / `TableDesc(description, columns, foreignKeys)` / `ColumnDesc(type, comment, nullable, key)`。相比数据库实时自省的价值：能承载 DDL 注释之外的业务语义（比如说明"某字段的'活跃'和另一处'活跃'不是一个概念"）、可以人工维护示例值、可以描述视图背后已做了哪些 JOIN/聚合。`SchemaCatalogService` 用 `@PostConstruct` 启动预加载 + 内存只读 Map，全生命周期只读一次，天然线程安全无需加锁。
  - **代价必须写进设计文档而不是隐藏**：需要人工维护和审核；漂移是静默的——YAML 和真实库结构不一致时不报错，只会导致 LLM 按过时字段生成 SQL 查不出数据/运行时报错。建议纳入 Git 管理并在 CI 里跑一个"YAML 声明的表/字段是否仍存在于真实库"的漂移检测脚本（这是本文档新增的建议，源笔记只提到了风险没给出检测方案）。
- **视图处理决策**：如果 SQL 场景库（sakila）自带的视图会绕过行级权限过滤（视图内部 JOIN 掉了 `dept_id`/`user_id` 所在的表），一律不暴露给 `listTables`，汇总类查询改走基础表+显式 JOIN，让权限改写（5.5）始终能定位到真实来源表。

### 5.2 Schema 工具封装：两阶段渐进式披露

`ListTablesTool`（表名+描述+关联关系）→ `DescribeTablesTool`（展开指定表的完整字段详情）。SKILL.md 里明确约束调用顺序，不是一次性把所有表结构塞进 system prompt——几十张表的 schema 就有几 KB，全塞进去噪声会稀释模型注意力、拉低准确率。

### 5.3 业务消歧：Glossary + 探针 SQL

- `GlossaryDesc(term, description, sqlFragment, synonyms)`，YAML 维护（跟 Git 走、版本可追溯，改 schema 时术语口径可以顺便 review）。
- `LookupGlossaryTool` 用 `FunctionToolCallback` 而不是 `@Tool` 注解注册——关键原因：`@Tool` 的 description 必须是编译期常量，无法把运行时加载的术语列表动态拼进工具描述；`FunctionToolCallback` 能在创建工具时动态生成 description，让模型在 Tool Selection 阶段就"看到"当前有哪些术语可查。
- **精确匹配优先于同义词匹配，明确不用向量检索**：术语名精确匹配 → 显式维护的同义词精确匹配（`termIndex`/`synonymIndex`，小写化后查 Map）→ 都不命中就诚实返回"没找到，这是全部术语列表"。原因（踩坑点 #27）：向量相似度会把"活跃客户"和"高价值客户"这种字面相似但业务口径完全不同的词匹配到一起，一个似是而非的错误匹配比"没匹配到"更危险——原则是"宁可不命中，也不能命中错"。
- **时间锚点元规则**（踩坑点 #28）：如果 SQL 场景库是像 sakila 这样冻结的历史快照库，必须在 glossary 里登记一条元规则——所有相对时间（"近3个月"）基于 `(SELECT MAX(rental_date) FROM rental)` 计算而不是 `NOW()`，否则结果永远是空的。这个原则同样适用于任何真实的"历史快照型"数据分析场景（离线数仓、审计日志分析）。
- 探针 SQL 作为 glossary 之外的补充手段：取枚举值、模糊查特定值分布、看字段真实样本——与示例值的区别在于探针是实时查询（准但慢），示例值是启动时采样的静态快照（快但可能过时）。

### 5.4 SQL 安全校验：AST 而非正则

- `SqlSafetyGuard` 是唯一的校验实现，被 `validateSql`（LLM 主动预检）和 `executeSql`（执行前强制重新校验）共享同一份逻辑，不允许出现两份可能漂移的校验代码。
- 用 **JSqlParser** 解析 SQL 成 AST，`DangerousFunctionFinder` 继承 `TablesNamesFinder`（本身自带完整 Visitor 递归遍历能力），重写 `visit(Function)` 检测所有函数调用，包括嵌套在参数里的（递归 `super.visit(function)`）。
- **为什么必须是 AST 而不是正则**（踩坑点 #22）：正则两头堵不住——字符串字面量里出现 `LOAD_FILE() 演示` 这种文本会被误伤（假阳性）；MySQL 允许函数名和括号间插注释（`LOAD_FILE/**/('...')`）能绕过正则（假阴性）。AST 里字符串字面量是 `StringValue` 节点，不会被误判成 `Function` 节点。
- 校验流水线（任一步不过直接拒绝并返回**具体到规则**的失败原因，让模型能自洽重写而不是拿到笼统的"校验失败"）：
  1. 文本预扫危险模式（`LOAD_FILE(`/`INTO OUTFILE`/`SYSTEM_USER(` 等）
  2. 解析后拦截多语句（`stmts.size() != 1`）
  3. 只允许 `SELECT`/`WITH`（CTE 放行，`INSERT`/`UPDATE`/`DELETE`/DDL/`CALL` 全拒绝）
  4. AST 遍历拦截危险函数（`sleep`/`benchmark`/`load_file` 等）
  5. 拦截 `FOR UPDATE`/`SELECT INTO`
  6. 查询形态校验：`OFFSET` 必须配 `ORDER BY`、JOIN 数量上限（默认 3）、禁止 `CROSS JOIN`、禁止无条件 JOIN，且**递归**检查 CTE/UNION/子查询/JOIN ON 里嵌套的子查询
  7. `LIMIT` 处理：超阈值下调，未写自动注入（默认阈值 200 行）
- **Fail-closed 原则**：解析或校验出现任何异常，一律拒绝，不放行任何拿不准的查询——这不是 Workflow，是 ReAct，Skill 提示词写得再严格，模型也不可能每次都完全遵守，安全性不能寄托在模型自觉上。
- `ValidationResult(valid, reason, safeSql)`，`reason` 精确到十种以内的具体失败原因文案。

### 5.5 executeSql 执行流水线（6 步，消费 Phase 2A 的 `DataScopeResolver`）

1. **兜底重新校验**：无条件重新跑一遍 `SqlSafetyGuard`（不信任调用方是否已经调过 `validateSql`）
2. **数据权限改写**：见下方独立小节
3. **EXPLAIN 预检查**（本文档从笔记里补充的、roadmap 完全没提到的细节）：`EXPLAIN` 超时（默认 5 秒）直接拦截；执行计划预计扫描/组合行数超过阈值（默认 10 万行）直接拦截；判断保持克制——小表全表扫描放行、无索引但预计行数小放行、聚合查询（`COUNT`/`SUM`/`GROUP BY`）优先放行、只有明细查询扫描量过大或 JOIN 组合量过大才拦截；非超时类异常一律放行（避免驱动兼容性问题影响可用性）
4. **只读执行**：`trySetReadOnly(conn)` 仅作提示（try-catch 忽略失败——MySQL Connector/J 对这个标志的处理本身有已知 bug，`WITH`/带注释的 `SELECT` 可能被误判，不能当作安全边界）+ `TYPE_FORWARD_ONLY/CONCUR_READ_ONLY` 结果集（阻止 `updateXxx` 回写）+ 查询超时（默认 30 秒）+ `setMaxRows(maxRows+1)`（多查一行判断是否被截断，省一次 `COUNT(*)` 往返，踩坑点 #23）
5. **敏感字段脱敏**：见 5.6
6. **结果格式化**：`normalizeValue` 归一化（`BigDecimal` 去尾零、时间统一 ISO 字符串、`byte[]` 用占位符）；只渲染前 20 行预览，超出提示改分页/聚合查询，**明确告诫模型不要从预览行里手算总量/均值**；空结果单独分支引导模型查 `COUNT(*)` 或去条件重查（空结果对模型是歧义场景，需要显式引导）

**JDBC 异常按 SQLState 分类**（踩坑点 #24）：`TRANSIENT`（连接异常/死锁，内部自动重试 2 次、指数退避，对模型完全透明——避免瞬态故障污染模型推理上下文）/ `SCHEMA`（提示模型重新调 `describeTables`）/ `SYNTAX`（提示检查语法）/ `FATAL`（权限不足或无法识别，告知无法继续）。

**userId 获取机制**：不走 system prompt 让模型自己传（长 ID 容易抄错，且这类运行时真值本来就不该交给模型传递），复用 Phase 2A 已经接好的 `RunnableParams.toolParams` 强制注入通道——Controller 在原始 HTTP 线程冻结 `userId`，工具执行前统一覆盖模型填的任何值（漏传、乱填、抄占位符都会被覆盖，见踩坑点 #35）。`executeSql` 自己还要有兜底：拿到的 `userId` 若为空或明显的占位符值，直接拒绝执行并记错误日志，不静默放行。

### 5.6 数据权限 AST 改写

**边界声明**：`DataScopeResolver`（Phase 2A 已交付）负责"这个用户能看到哪些部门"，本节的 `DataScopeRewriter` 负责"把这个结果拼进 SQL"——两者是消费关系，不重新设计前者。

- 为什么不能字符串拼接（踩坑点 #26，这是全篇技术含金量最高的一条）：
  - **运算符优先级坑**：原 SQL 若有 `WHERE status=1 OR amount>100`，字符串拼接 `AND dept_id IN(...)` 到末尾后，因为 `AND` 优先级高于 `OR`，实际变成 `status=1 OR (amount>100 AND dept_id IN(...))`——权限条件被 `OR` 短路，`status=1` 这一支完全不受过滤约束，**数据直接泄漏**。
  - **LEFT JOIN 语义破坏**：权限条件如果对 `LEFT JOIN` 的右表加，放进 `WHERE` 会让右表不匹配的行连带左表行一起被过滤掉——`LEFT JOIN` 退化成 `INNER JOIN`，不是权限收紧而是查询语义被破坏，且这种错误"能跑通但结果集悄悄变少"，不会报错，排查难度很高。
- 解法：JSqlParser 解析成 AST，`walk` 递归遍历（`PlainSelect` 注入条件 + 递归 `FROM`/`JOIN` 子查询 + 递归 `WHERE` 子查询；`SetOperationList`/`UNION` 每个分支各自递归）。`injectConditions` 按表在查询里的位置决定插入点：普通表/`INNER JOIN` 右表条件放 `WHERE`，`LEFT JOIN` 右表条件必须放进那个 `JOIN` 自己的 `ON` 子句。
- `PermissionRule` 策略接口（`managedTables()` + `buildCondition(Table, DataScopeContext)`，返回 JSqlParser `Expression` 节点而非字符串），`PermissionRuleRegistry` 按表名路由，没配专属规则的表走 `DefaultPermissionRule`（假设有 `user_id`+`dept_id` 列）兜底。多对多关联表（比如用户和部门的中间表）用 **`EXISTS` 子查询而不是 JOIN**——JOIN 会因为一对多关系改变结果集行数，`EXISTS` 只判断"有没有"，不会让一个用户因为挂了多个部门就在结果里重复出现。
- `DataScopeResolver` 输出的 `deptId` 列表为空/角色数据缺失时，`DataScopeRewriter` 直接抛 `RewriteException`，不放行未过滤的 SQL（fail-closed，和 Phase 2A 的原则一致）。

### 5.7 敏感字段脱敏

**边界声明**：数据权限解决"哪些行能看"，脱敏解决"哪些列值不能看"——两者独立机制，互不替代。`SensitiveFilter` 在链路里位置很靠后：SQL 已执行完，处理的是内存里的结果集。

- 配置：`mask-fields: sys_user.password,user_profile.id_card,...`，同时维护两个集合——`sensitiveFields`（`table.column` 全名，精确匹配真实来源）和 `sensitiveColumns`（纯字段名，拿不到来源表时兜底）。
- **必须按 `ResultSetMetaData.getColumnName()`/`getTableName()`（真实来源列名/表名）匹配，不能只看 `getColumnLabel()`（展示名）**（踩坑点 #25）：`SELECT password AS pwd FROM sys_user` 这种别名查询，如果只按展示列名 `pwd` 匹配敏感字段名单会直接漏判——展示名不在名单里。
- 三处独立覆盖点（容易只做一半，必须都覆盖）：① `executeSql` 结果集；② M-Schema 采样示例值（采样前跳过敏感字段，比采样后再替换更干净——敏感值从一开始就不进缓存）；③ 拼进 system prompt 的用户画像信息（如果 Phase 2A 的用户上下文构造逻辑会展示身份证/住址等字段，同样要过一遍脱敏）。
- V1 是全局脱敏（命中字段一律显示 `********`，不区分谁在查）。留一个可扩展点：按角色/数据范围做精细化控制（管理员可看明文），但这次不实现，写进 Out of Scope。

### 5.8 复杂计算工具

- `CalculateTool` 定位精确：**不查数据库、不做大批量聚合，只做最终标量公式计算**（环比、占比、贡献度这类）。批量聚合永远交给 SQL 的 `GROUP BY`+`SUM`/`AVG`。
- 入参 `expression`（如 `round((current-previous)/previous*100, 2)`）+ `variablesJson`（如 `{"current":3298,"previous":3105}`）。用轻量表达式引擎（如 **exp4j**）求值，只支持数学运算符和内置函数，不暴露任何系统调用能力。
- **明确不让模型自己算，也不让它用 Bash 绕过工具栈**（踩坑点 #29，真实教训）：早期让模型写 Python 脚本用 Bash 执行，一是烧 token，二是模型会"自作主张"直接用 Bash 连数据库跑探测查询，绕开前面辛苦搭的安全校验/权限改写/脱敏整套工具栈——DataAgent 绝对不能挂 Bash 工具，这条和 Runtime 通用的 Bash 内置工具（Phase 0.9）是完全隔离的两个工具集。

### 5.9 图表生成

沿用 roadmap Phase 5 已定的 `mcp-echarts`（streamable HTTP，不用 stdio——stdio 在并发多用户多轮场景下"第一次调用成功、后续被拒绝"）+ 对象存储（Artifact URL，不把 base64 塞进上下文）。这里不重复设计，只强调 DataAgent 侧的调用方式是把 `calculate`/聚合查询的结果传给图表工具，工具本身在 Phase 5 已有归属。

### 5.10 评测体系（新增设计，roadmap 尚未覆盖）

Golden Tasks 集合覆盖以下维度（延续 `dataagent-design-audit-2026-08-03.md` 的上线门禁要求，这里给出具体落地形态）：

- **SQL 正确率**：每条任务预置"自然语言问题 + 预期 SQL 或预期结果集（数值/行数容差）"，跑 DataAgent 生成的 SQL 执行后对比结果而不是逐字符比对 SQL 文本（同一问题存在多种正确写法）
- **越权检测**：为每个 `data_scope` 档位（`ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF`）各准备至少一个测试账号，同一问题用不同账号执行，断言返回行严格落在该账号的可见范围内，且**多部门用户的结果是所有挂载部门的并集**（不是只读到第一个关联记录）
- **敏感字段泄漏检测**：故意让测试问题涉及 `mask-fields` 里的列（含用 `AS` 别名的变体），断言返回值必须是掩码而不是明文
- **空结果解释**：断言查询结果为空时返回的引导文案存在（不是裸的空数组）
- **重试次数 / 延迟 / Token**：记录每条任务实际触发的 `TRANSIENT` 重试次数、端到端延迟、Token 消耗，作为性能基线
- **可复现性**：同一问题多次运行，SQL 生成路径可能不同，但最终结果集应该一致（排除受当前时间影响的查询，这类要用 5.3 的时间锚点规则固定基准时间）

落地位置：新增测试模块（比如 `analytics-golden-it`），复用 Phase 0.0 已有的 `ScriptedLlmClient` 和 Ticket 06 的 `AnalyticsLocalDbTestSupport`（连本机真实 MySQL），不新造一套测试框架。按工程约束审计的结论，这套评测是 DataAgent 的**上线前置门禁**，不是锦上添花的后续工作——SQL 安全、权限改写有任何改动，都必须先过这套回归集。

### 5.11 中断恢复语义（新增设计）

DataAgent 是开放式 ReAct+Skill（不是固定 DAG），Phase 1 已有的通用 `PauseState`/`SafePoint` 断点恢复机制天然适用，**不需要为 SQL 分析设计专属的子阶段状态机**——这一点本身是 ADR-0003"不把七阶段硬编码成不可变 DAG"的直接推论。

本节需要补充一条明确的边界声明：**DataAgent 目前挂载的全部工具都是只读或纯函数**（`listTables`/`describeTables`/`lookupGlossary`/`validateSql`/`executeSql`——只读查询；`calculate`——纯函数求值），这意味着中断发生在 `SafePoint.TOOL_EXECUTION` 阶段、恢复时重新执行同一个 `pendingToolCall`，**不会有踩坑点 #34 描述的“非幂等操作重复执行”风险**——不需要为这些工具设计幂等键/upsert。唯一需要单独考虑的是图表生成（写 MinIO 对象存储）：生成对象使用**内容确定性的 key**（例如对渲染输入做哈希）而不是随机文件名，避免恢复后重做同一张图表产生孤儿文件。

如果未来 Phase 2 之后要给 DataAgent 加写类能力（当前完全没有计划，只是为了让这条边界声明更完整），届时才需要引入幂等设计——这不是这次交付范围。

### 5.12 多节点部署（新增设计，roadmap 尚未覆盖 DataAgent 专属部分）

Phase 1 的 Redis 分布式任务锁 + Pub/Sub 跨实例中断已经覆盖“任务级”的多实例协调，这里不重复。DataAgent 仍需补齐两个专属问题：

- **M-Schema 缓存的并发刷新保护**：`MschemaCacheService` 的定时刷新如果多个实例同时到点触发，会有多个实例并发跑内省+写 Redis（内省本身对数据库有一定压力，且存在"后写覆盖先写"的竞态，虽然数据一致不会错，但是浪费）。解法：复用 Phase 1 已有的 `RedisTaskLock` 工具，刷新前抢一把短 TTL 的锁（比如 60 秒），抢不到的实例跳过本次刷新，直接读其他实例已经刷好的 Redis 缓存——不需要引入新的分布式协调机制，`RedisTaskLock` 现成可用。
- **SQL 目标库只读连接池容量核算**：`executeSql` 连接的是 SQL 场景库（sakila），这是一个和 AgentTrail 自身元数据库（会话/审计/记忆）**物理隔离的独立连接池**（不要复用同一个 HikariCP 实例，两者的流量特征、超时策略、故障域都不同）。按 Phase 1 已有踩坑点 #33 的方法论——连接池大小 = (目标库 `max_connections` × 安全系数) / 实例数，做成可由部署环境覆盖的配置项，不写死常量。

部门树范围解析（`ancestors LIKE` 前缀查询）不需要额外设计——见第 1.3 节，这是 Phase 2A 选型自带的架构优势，不存在进程内邻接树的跨节点一致性问题。

## 6. Testing Decisions

- 集成测试连本机真实 MySQL/Redis，禁用 H2——SQL AST 校验、权限改写在 H2 上行为和 MySQL 不同，会掩盖 bug。Phase 2B 不用 Testcontainers（业务数据已经导进本机库，理由见 Ticket 06 第 8 节），连不上时用 `Assumptions` 跳过
- `SqlSafetyGuard` 需要覆盖：AST 检测能拦住"函数名+注释绕过正则"这种攻击样本（不能只测最朴素的攻击字符串）、多语句注入、危险函数嵌套在参数里的场景
- `DataScopeRewriter` 需要覆盖：运算符优先级坑的回归用例（`OR` 条件下权限注入必须整体加括号或改写成不受短路影响的形式）、`LEFT JOIN` 权限条件必须验证在 `ON` 而不是 `WHERE`、多对多关联表验证 `EXISTS` 不产生行数膨胀
- `SensitiveFilter` 需要覆盖：别名绕过用例（`SELECT password AS pwd`）必须仍被脱敏
- 5.10 的 Golden Tasks 作为独立回归套件，SQL 安全/权限改写代码合入前必须全绿
- EXPLAIN 预检查需要覆盖：超时降级放行的行为、大表全表扫描拦截、聚合查询优先放行不误伤

## 7. Out of Scope

- 敏感字段脱敏的角色精细化控制（管理员可见明文）——V1 全局脱敏，架构留了扩展点，不在本次交付
- DataAgent 的写类能力——当前只读，5.11 的幂等设计只是边界声明不是本次要实现的功能
- YAML Schema 的自动漂移检测 CI 脚本——5.1 提到的建议，具体实现是否值得投入按后续优先级决定，不在本次交付强制范围
- Phase 3 通用治理框架（Hooks 生命周期、审计哈希链、成本治理）——本文档只负责列出 DataAgent 要挂哪些 Hook 点，Hooks 框架本身是 Phase 3 的范围
- 图表生成工具本身的实现——归属 Phase 5，本文档只描述 DataAgent 如何调用

## 8. 设计证据映射

- Schema/消歧/SQL 安全/执行/权限改写/脱敏/计算：以当前领域模型、威胁模型、SQL AST 行为和集成测试结果为设计依据
- 评测/中断恢复/多节点三个新增设计点：以当前测试缺口、恢复语义和部署约束为依据独立设计
