# Ticket 11／18：execute_sql 执行流水线（EXPLAIN 预检 + 只读执行 + 脱敏 + 格式化）— 技术开发文档

> GitHub issue: [#57](https://github.com/renjian-pro/AgentTrail/issues/57)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.5／5.7 节。`Blocked by` [Ticket 6](backend-phase2-sql-ticket-06.md)、[Ticket 9](backend-phase2-sql-ticket-09.md)（`SqlSafetyGuard`）、[Ticket 10](backend-phase2-sql-ticket-10.md)（`DataScopeRewriter`）。这是把前面所有零件组装成一条完整链路的一票。
>
> 这一票交付后，DataAgent 就具备完整的"问一句话 → 拿到一张安全的结果表"能力（工具还没挂上执行器，那是 Ticket 12）。

## 0. 范围边界

**这一票只做**：`execute_sql` 这一个工具，以及它内部的六步流水线。

**这一票不做**：
- `calculate` 工具、SKILL.md、执行器装配（Ticket 12）
- Golden Tasks（Ticket 13）
- 修改 `SqlSafetyGuard` 或 `DataScopeRewriter`——它们是这一票的**被调用方**，如果发现它们有 bug，回到对应票去修，不要在这里绕过

## 1. 六步流水线（顺序不能变，每一步都假设前一步可能被绕过）

```
execute_sql(sql, userId)
  ①  SqlSafetyGuard.validate(sql)         ← 无条件重跑，不管模型有没有先调 validate_sql
  ②  DataScopeRewriter.rewrite(safeSql, ctx)  ← ctx 来自 DataScopeResolver.resolve(userId)
  ③  ExplainPrecheckService.precheck(rewrittenSql)
  ④  ReadOnlyQueryRunner.run(rewrittenSql)
  ⑤  SensitiveFilter.mask(result)
  ⑥  SqlResultFormatter.format(masked)
```

### 1.1 为什么第 ① 步要无条件重跑校验

`validate_sql` 是模型**自己选择性调用**的工具。这是 ReAct 不是 Workflow——SKILL.md 里写得再严格，模型也完全可能跳过它直接调 `execute_sql`。安全性不能寄托在模型的自觉上，所以 `execute_sql` 内部重新跑一遍完整校验，不信任调用方是否已经校验过。

多跑一次的成本是几毫秒的 AST 解析（不碰数据库），换来的是"这条路径无论怎么走都过了校验"这个确定性。

### 1.2 为什么校验在改写之前

改写会往 SQL 里插入条件，改完的 SQL 和模型写的已经不是同一条。**先校验原始输入**：如果模型写的 SQL 本身就危险，要在它被我们加工之前就拒绝，错误信息也才对得上模型自己写的东西。反过来（先改写再校验）会出现"模型看不懂为什么自己写的 SQL 报了一个它没写过的错"。

## 2. 验收标准

**流水线整体**
- [ ] 模型不调 `validate_sql` 直接调 `execute_sql` 传一条 `DROP TABLE`，被拒绝（证明第 ① 步生效）
- [ ] 同一条 SQL，`analyst_test`（DEPT）和 `admin`（ALL）两个账号执行，前者结果行数严格小于后者（证明第 ② 步生效）
- [ ] `userId` 传空/传 `"default"`/传别人的 id → 拒绝执行并打 error 日志（见 5.1）

**EXPLAIN 预检**
- [ ] 预计扫描行数超阈值的明细查询被拦截，返回可操作的建议文案
- [ ] 聚合查询（`COUNT`/`SUM`/`GROUP BY`）即使扫描行数大也放行
- [ ] 小表全表扫描放行
- [ ] EXPLAIN 本身超时 → 拦截
- [ ] EXPLAIN 抛非超时异常（比如语法在 EXPLAIN 下不支持）→ **放行**，不因为预检机制的兼容性问题挡住正常查询

**只读执行**
- [ ] 结果集是 `TYPE_FORWARD_ONLY` + `CONCUR_READ_ONLY`
- [ ] 查询超时生效（写一个 `SELECT SLEEP(...)` 类的用例——注意 `SLEEP` 被 Ticket 9 禁了，改用一条真正的慢查询或直接把 timeout 配成极小值）
- [ ] `setMaxRows(maxRows + 1)` 生效，且能据此正确判断结果是否被截断
- [ ] `columnMetas` 里同时拿到了 `getColumnLabel`/`getColumnName`/`getTableName` 三个值

**脱敏**
- [ ] `SELECT id_card FROM user_profile` → 值是 `********`
- [ ] **`SELECT id_card AS code FROM user_profile` → 值仍然是 `********`**（别名绕过用例，这是脱敏这一节的核心）
- [ ] 表达式列（`SELECT CONCAT(id_card, '')`）拿不到来源表名时，走纯字段名兜底匹配

**错误分类**
- [ ] 表不存在（SQLState `42S02`）→ 返回文案里建议调 `describe_tables`
- [ ] 语法错误（`42000`）→ 返回文案里提示检查语法
- [ ] 瞬态错误（`08xxx`/`40001`）→ **内部自动重试 2 次，模型完全看不到这次故障**
- [ ] 权限不足（`28xxx`）或 SQLState 为 null → 直接告知无法继续，不重试

**结果格式化**
- [ ] 正常结果只渲染前 20 行，超出时提示改分页/聚合，并明确告诫不要从预览行里手算总量
- [ ] 空结果走独立分支，引导查 `COUNT(*)` 或放宽条件，不是返回一个裸的空表格
- [ ] `BigDecimal` 去尾零、时间统一 ISO 字符串、`byte[]` 显示为占位符

## 3. 新增文件清单

包根：`com.agenttrail.capability.analytics`

| 文件 | 职责 |
|---|---|
| `sql/ExplainPrecheckService.java` | 跑 `EXPLAIN`，判断是否放行。见第 4 节 |
| `sql/ReadOnlyQueryRunner.java` | 真正执行查询，产出 `SqlResult`。见第 5 节 |
| `sql/SqlResult.java` | record `SqlResult(List<ColumnMeta> columns, List<List<Object>> rows, boolean truncated, long elapsedMs)` |
| `sql/ColumnMeta.java` | record `ColumnMeta(String label, String columnName, String tableName)` |
| `sql/SqlErrorClassifier.java` | `SQLException` → `SqlErrorKind`（`TRANSIENT`/`SCHEMA`/`SYNTAX`/`FATAL`）+ 给模型的建议文案 |
| `mask/SensitiveFilter.java` | 结果集脱敏。见第 6 节 |
| `sql/SqlResultFormatter.java` | `SqlResult` → 给模型看的 Markdown 文本。见第 7 节 |
| `tools/ExecuteSqlTool.java` | 组装六步流水线，产出 `ToolCallback` |
| `config/AnalyticsExecutionProperties.java` | `@ConfigurationProperties("agenttrail.analytics.execution")`：`explainTimeoutSeconds`、`maxEstimatedRows`、`queryTimeoutSeconds`、`maxRows`、`previewRows`、`transientRetries` |

## 4. `ExplainPrecheckService`

### 4.1 定位

Ticket 9 的 `SqlSafetyGuard` 检查的是**语法结构**（这条 SQL 会不会干坏事）。EXPLAIN 预检查检查的是**执行代价**（这条 SQL 会不会把库拖垮）。两者互不替代：一条 `SELECT * FROM rental r1 JOIN rental r2 ON r1.customer_id = r2.customer_id` 语法上完全合规，执行起来是灾难。

### 4.2 判断规则（保持克制，宁可放行也不要误伤）

```java
public PrecheckResult precheck(String sql) {
    try (Connection conn = dataSource.getConnection();
         Statement stmt = conn.createStatement()) {
        stmt.setQueryTimeout(explainTimeoutSeconds);
        // MySQL：EXPLAIN FORMAT=JSON 能拿到更准的 rows 估算，但解析 JSON 更麻烦；
        // 先用传统 EXPLAIN 读 rows 列，各行 rows 相乘得到 JOIN 组合量的粗估
        try (ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
            long estimated = productOfRowsColumn(rs);   // 各行 rows 连乘，注意溢出保护
            if (estimated > maxEstimatedRows && !isAggregate(sql)) {
                return PrecheckResult.block("""
                        这条查询预计需要扫描/组合约 %d 行，超过上限 %d，已拦截。
                        建议：① 加更严格的 WHERE 条件缩小范围；② 改成聚合查询
                        （COUNT/SUM/GROUP BY）而不是拉明细；③ 拆成多次小查询。
                        """.formatted(estimated, maxEstimatedRows));
            }
            return PrecheckResult.pass();
        }
    } catch (SQLTimeoutException timeout) {
        // 连执行计划都算不出来，说明这条 SQL 复杂到不正常，拦
        return PrecheckResult.block("生成执行计划超时，这条查询过于复杂，请简化后重试");
    } catch (SQLException other) {
        // 非超时异常一律放行：EXPLAIN 对某些语法的支持度和真实执行不完全一致，
        // 不能因为预检机制本身的兼容性问题挡住一条正常查询。
        // 真正的兜底是下一步的 queryTimeout + maxRows，不靠这一层
        log.debug("EXPLAIN 预检查异常，放行交给执行层兜底: {}", other.getMessage());
        return PrecheckResult.pass();
    }
}
```

`isAggregate(sql)` 的判断：AST 里存在 `GROUP BY`，或者 SELECT 列表里全是聚合函数（`COUNT`/`SUM`/`AVG`/`MIN`/`MAX`）。**复用 Ticket 9 已经解析过的 AST**，不要为这个判断再解析一次 SQL——`SqlSafetyGuard.validate` 可以顺便把解析结果带出来，或者这里接受一个已解析的 `Statement` 参数。

聚合查询优先放行的理由：聚合的返回行数很少，扫描行数大但内存和网络代价可控，而这恰恰是数据分析最常见的形态。一刀切拦截会让 DataAgent 连"总共有多少笔订单"都答不了。

## 5. `ReadOnlyQueryRunner`

### 5.1 userId 兜底校验（放在 `ExecuteSqlTool` 里，不在 Runner 里）

```java
// AgentLoopController:54 已经把真实 userId 放进 RunnableParams.toolParams，
// ToolParamInjector 会在工具执行前按 inputSchema 白名单强制覆盖模型填的任何值。
// 但这一层仍然要兜底——万一装配出错（工具 inputSchema 没声明 userId、
// 或者某个调用路径没走 Controller），我们要的是"拒绝执行"而不是"用一个空 userId 查全库"
String userId = args.text("userId");
if (userId == null || userId.isBlank() || "default".equals(userId) || "anonymous".equals(userId)) {
    log.error("execute_sql 收到非法 userId（{}），拒绝执行。检查 RunnableParams.toolParams 装配", userId);
    return "Error: 无法确定当前用户身份，查询被拒绝";
}
```

工具的 `inputSchema` **必须显式声明 `userId` 字段**，否则 `ToolParamInjector` 的白名单过滤会把它挡掉，注入不进来。这一点在 `FileContentTool` 的 `conversation_id` 上已有先例（`loop/tools/FileContentTool.java:44`），照着做。

### 5.2 四层 JDBC 约束

```java
try (Connection conn = dataSource.getConnection()) {
    // ① 只读提示。注意：MySQL Connector/J 对这个标志的实现有已知缺陷——它靠检查
    //    SQL 开头几个字符判断是不是 SELECT，WITH 开头的 CTE 或带前导注释的 SELECT
    //    可能被误判成写操作直接拒绝。所以 try-catch 忽略失败，把它当提示不当保证；
    //    真正的只读保证是 Ticket 6 的数据库账号权限
    try { conn.setReadOnly(true); } catch (SQLException ignored) { }

    // ② 结果集不可更新：即使拿到 ResultSet 也没法 updateXxx 回写
    try (PreparedStatement stmt = conn.prepareStatement(sql,
            ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
        // ③ 查询超时
        stmt.setQueryTimeout(queryTimeoutSeconds);
        // ④ 多取一行：靠"第 maxRows+1 行有没有出现"判断是否被截断，
        //    省掉一次单独的 COUNT(*) 往返
        stmt.setMaxRows(maxRows + 1);
        ...
    }
}
```

第 ④ 层是个值得单独理解的技巧：想告诉模型"结果被截断了，还有更多数据"，朴素做法是先跑一次 `SELECT COUNT(*)` 再跑数据查询——两次往返。`setMaxRows(N+1)` 只跑一次：读到第 N+1 行就说明有更多数据（丢弃这一行，只用它做标记）。

### 5.3 `columnMetas` 的采集（脱敏的前提）

```java
ResultSetMetaData meta = rs.getMetaData();
for (int i = 1; i <= meta.getColumnCount(); i++) {
    columns.add(new ColumnMeta(
            meta.getColumnLabel(i),    // 展示名，受 AS 别名影响
            meta.getColumnName(i),     // 真实源列名，不受别名影响 ← 脱敏靠这个
            meta.getTableName(i)));    // 真实源表名 ← 脱敏靠这个
}
```

三个值都要存。`getColumnLabel` 给渲染用（用户看到的表头应该是他要的别名），`getColumnName`/`getTableName` 给脱敏用。**这两组不能互相替代**，见第 6 节。

### 5.4 瞬态重试

```java
// TRANSIENT 类错误（连接中断 08xxx、死锁 40001）内部重试 2 次，指数退避。
// 关键决策：重试对模型完全透明——不把"第一次失败了"这件事告诉它。
// 理由：瞬态故障是基础设施问题，不是模型的 SQL 有问题；把它暴露给模型
// 只会污染推理上下文，让模型开始怀疑自己的 SQL 并去改一条本来正确的查询
```

只有 `TRANSIENT` 重试，其余三类立刻返回。

## 6. `SensitiveFilter`

### 6.1 定位

数据权限（Ticket 10）解决"哪些**行**能看"，脱敏解决"哪些**列值**不能看"。两者是正交的独立机制，互不替代：一个部门主管完全有权看到下属的 `user_profile` 这一行（它在他部门里，权限改写会放行），但仍然不该看到这一行的 `id_card` 列。

`SensitiveFilter` 在链路里位置很靠后——SQL 已经执行完，它处理的是内存里的结果集。

### 6.2 两份名单

```java
// 配置：agenttrail.analytics.mask-fields: [user_profile.id_card, user_profile.home_address, ...]
// 启动时拆成两个集合：
private final Set<String> sensitiveFields;    // "user_profile.id_card" 全名，精确匹配真实来源
private final Set<String> sensitiveColumns;   // "id_card" 纯列名，拿不到来源表时兜底
```

### 6.3 匹配优先级（别名绕过是这一节的核心）

```java
private boolean isSensitive(ColumnMeta column) {
    // ① 优先按"真实来源表.真实来源列"精确匹配
    if (column.tableName() != null && !column.tableName().isBlank()) {
        String qualified = (column.tableName() + "." + column.columnName()).toLowerCase(Locale.ROOT);
        if (sensitiveFields.contains(qualified)) return true;
    }
    // ② 拿不到来源表时（表达式列、函数列、子查询列）按纯列名兜底。
    //    这一层会有误伤（任何叫 id_card 的列都被脱敏），但方向是安全的
    if (sensitiveColumns.contains(lower(column.columnName()))) return true;
    return sensitiveColumns.contains(lower(column.label()));
}
```

**为什么不能只看 `getColumnLabel`**：`SELECT id_card AS code FROM user_profile` 这条 SQL 的展示列名是 `code`，不在名单里 → 只按展示名匹配的实现会**直接放行明文身份证号**。这是一个只要写个别名就能绕过整套脱敏的洞。验收标准里有专门用例。

### 6.4 三处覆盖点（容易只做一半）

| 位置 | 归属 | 状态 |
|---|---|---|
| `execute_sql` 结果集 | 这一票 | 本节 |
| M-Schema 示例值采样 | Ticket 7 已做（采样前跳过敏感字段，比采样后再脱敏更干净——敏感值从一开始就不进 Redis） | 已完成 |
| 拼进 system prompt 的用户信息 | 如果 Ticket 12 的装配会把用户档案写进提示词，那里也要过一遍 | Ticket 12 检查 |

三处共用**同一份** `mask-fields` 配置，不要各配各的。

### 6.5 V1 是全局脱敏

命中字段一律 `********`，不区分谁在查。按角色放开明文（管理员可见）是明确的 Out of Scope——但 `isSensitive` 的签名要留出扩展余地（未来加一个 `DataScopeContext` 参数即可），不要写成静态方法。

## 7. `SqlResultFormatter`

三个分支，**每个分支的文案都要能指导模型下一步动作**：

### 7.1 正常结果

```
查询成功，共 156 行（耗时 234ms）。以下是前 20 行：

| customer_id | name | total |
|---|---|---|
| 1 | MARY SMITH | 118.68 |
...

（结果超过 20 行，只展示前 20 行。不要从这 20 行里手工计算总和或平均值——
那样算出来的一定是错的。需要总量请改写成聚合查询（SUM/AVG/COUNT），
需要看更多明细请加 LIMIT/OFFSET 分页并带上 ORDER BY。）
```

最后那段告诫不是废话——模型看到一张表格会本能地想从里面加总，这是真实会发生的错误。

### 7.2 空结果（独立分支，不能返回空表格）

```
查询执行成功，但没有匹配任何数据（0 行）。

空结果通常意味着以下之一：
① 过滤条件太严格 —— 试试去掉部分条件，或先用 COUNT(*) 确认表里有多少数据
② 时间范围不对 —— 本数据集是历史快照，"最近N天"要基于数据最大时间算，
   不能用 NOW()，先调 lookup_glossary 查时间口径
③ 值的形态和你假设的不一致 —— 用 SELECT DISTINCT <字段> ... LIMIT 10 探一下真实取值
④ 确实没有符合条件的数据 —— 这也是一个有效结论，可以直接告诉用户

不要在没有确认原因的情况下就断言"没有数据"。
```

空结果对模型是个歧义场景（是查错了还是真的没有？），不给引导它就会随机选一个方向，其中一半的情况会给用户一个错误结论。

### 7.3 失败

回显实际执行的 SQL（**改写后的，不是模型写的那条**——让模型看到权限条件被注入后的真实形态）+ `SqlErrorClassifier` 给出的分类建议。

### 7.4 值归一化

```java
// BigDecimal: stripTrailingZeros().toPlainString()，避免 118.6800 和 1.5E+2 这类形态
// java.sql.Date/Time/Timestamp: 统一转 ISO-8601 字符串
// byte[]: 不渲染内容，输出 "<binary N bytes>" —— 二进制塞进上下文毫无意义且极占 token
// null: 输出 "NULL" 而不是空串（空串和"空字符串值"分不清）
```

## 8. `execute_sql` 工具定义

```java
new JsonToolCallback("execute_sql", """
        在分析数据库上执行一条只读 SQL 查询并返回结果。

        安全与权限是自动的，你不需要（也不应该）自己处理：
        - 系统会自动注入当前用户的数据范围过滤条件，你写的任何 dept_id/user_id
          过滤条件都会被服务端覆盖，不要自己写
        - 敏感字段会自动脱敏
        - 结果行数有上限，超出会提示你改用聚合或分页

        也可以用它做探针查询（SELECT DISTINCT <字段> ... LIMIT 10）来确认
        某个字段的真实取值形态，这比猜字段含义可靠。
        """,
        """
        {"type":"object","properties":{\
        "sql":{"type":"string","description":"【必填】要执行的 SELECT 查询"},\
        "userId":{"type":"string","description":"系统自动传入当前用户，不需要手动填写"}},\
        "required":["sql"]}""",
        args -> execute(args.text("sql"), args.text("userId")));
```

`userId` 必须出现在 `inputSchema` 里（否则 `ToolParamInjector` 注入不进来），但**不放进 `required`**——模型不该被要求填它，它由服务端注入。

## 9. 实现顺序

1. `SqlResult`/`ColumnMeta`/`SqlErrorClassifier` 三个小类 + `SqlErrorClassifier` 的纯单测（构造各种 SQLState 断言分类）
2. `SqlResultFormatter`（纯函数）+ 单测：正常/空/截断/各种值类型归一化，全部不需要数据库
3. `SensitiveFilter` + 单测：手工构造 `SqlResult`（含别名场景的 `ColumnMeta`）断言脱敏，**别名用例单独写一个**
4. `ReadOnlyQueryRunner` + 集成测试（继承 Ticket 06 的 `AnalyticsLocalDbTestSupport`，连本机分析库）：四层约束逐条验证 + `columnMetas` 三个值都拿到 + 截断判断
5. `ExplainPrecheckService` + 集成测试：大表明细拦截、聚合放行、超时拦截、非超时异常放行
6. `ExecuteSqlTool` 组装 + 端到端集成测试：用 Ticket 1 的两个不同 scope 账号跑同一条 SQL 断言行数差异
7. 瞬态重试测试（可以用一个会抛特定 SQLState 的 mock `DataSource` 做，不必真造死锁）

## 10. 明确禁止事项

- **不要**因为"模型已经调过 validate_sql"就跳过第 ① 步校验
- **不要**把校验放在改写之后（理由见 1.2）
- **不要**依赖 `Connection.setReadOnly(true)` 作为只读保证（驱动实现有缺陷，见 5.2）
- **不要**用 `SELECT COUNT(*)` 判断是否截断，用 `setMaxRows(N+1)`
- **不要**只用 `getColumnLabel` 做脱敏匹配（别名绕过，见 6.3）
- **不要**把 `TRANSIENT` 重试暴露给模型
- **不要**在 EXPLAIN 抛非超时异常时拦截查询
- **不要**给空结果返回一个裸的空表格
- **不要**在这一票里修改 `SqlSafetyGuard`/`DataScopeRewriter`
- **不要**把 `userId` 放进工具 `inputSchema` 的 `required` 里
- 其余共享约束同 Ticket 6/7

## 11. 和现有代码的边界

**新增**：`capability/analytics/sql`（补充几个类）、`capability/analytics/mask`、`tools/ExecuteSqlTool`。
**依赖（只读消费）**：Ticket 9 的 `SqlSafetyGuard`、Ticket 10 的 `DataScopeRewriter`、Phase 2A 的 `DataScopeResolver`、Ticket 6 的 `analyticsDataSource`。
**不碰**：`loop` 包下任何文件（Ticket 7 已经做完那边唯一需要的改动）。
