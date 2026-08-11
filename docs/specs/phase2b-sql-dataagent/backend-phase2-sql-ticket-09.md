# Ticket 09／18：SQL 安全校验（JSqlParser AST）+ validate_sql 工具 — 技术开发文档

> GitHub issue: [#55](https://github.com/renjian-pro/AgentTrail/issues/55)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.4 节。`Blocked by` [Ticket 7](backend-phase2-sql-ticket-07.md)（需要 `JsonToolCallback` 已 public）。**不依赖** Ticket 8/10，可并行。[Ticket 11](backend-phase2-sql-ticket-11.md) 强依赖这一票（`execute_sql` 内部要无条件重跑这里的校验）。
>
> 这一票是 DataAgent 的第一道安全边界。做错了不是"功能不好用"，是**生产库可以被一条模型生成的 SQL 打穿**。

## 0. 范围边界

**这一票只做**：`SqlSafetyGuard`（一个纯函数式的校验器：输入 SQL 字符串，输出"通过/拒绝+原因+修正后的安全 SQL"）+ 一个 `validate_sql` 工具。

**这一票不做**：
- 执行 SQL（Ticket 11）
- 数据权限改写（Ticket 10）
- EXPLAIN 预检查（Ticket 11——那是"执行计划层面的资源保护"，和这里的"语法结构层面的安全校验"是两件事）

**关键边界**：`SqlSafetyGuard` **不能碰数据库**。它只解析和检查 SQL 文本，不需要连接、不需要 `DataSource`。这样它的单测跑得飞快（不需要 Testcontainers），而且它可以被 `validate_sql` 和 `execute_sql` 两处安全地复用。

## 1. 开工前必须验证的技术假设

**JSqlParser 版本和 API 形态**——这是这一票唯一的高风险项：

1. JSqlParser 4.x 和 5.x 的包名/类名有变化（`net.sf.jsqlparser.statement.select.SelectBody` 在 5.x 被移除，`PlainSelect` 直接实现 `Select`；`TablesNamesFinder` 的泛型签名也变了）。**先去 Maven Central 查当前最新稳定版，把它加进 `pom.xml`，写一个最小的 Demo 测试**：解析 `SELECT a FROM t WHERE b = 1`，遍历出表名 `t`。跑通之后再往下写。
2. 确认 `TablesNamesFinder` 在你选的版本里的正确继承/使用方式（5.x 起它实现的是 `visitor` 接口族，重写方法的签名和 4.x 不同）。
3. 确认能解析 MySQL 方言的 `LIMIT n OFFSET m`、CTE（`WITH`）、`UNION`。
4. **把最终确定的版本号和 API 形态写进这份文档的追加说明**——Ticket 10 也要用 JSqlParser 操作 AST，两张票必须用同一个版本。

```xml
<dependency>
    <groupId>com.github.jsqlparser</groupId>
    <artifactId>jsqlparser</artifactId>
    <!-- 版本：查 Maven Central 最新稳定版，不要凭记忆写 -->
</dependency>
```

## 2. 验收标准

每一条都要有对应的单测（不需要数据库）：

**基础放行**
- [ ] `SELECT * FROM rental LIMIT 10` 通过
- [ ] `WITH t AS (SELECT ...) SELECT * FROM t` 通过（CTE 必须放行）
- [ ] `SELECT ... UNION SELECT ...` 通过

**语句类型**
- [ ] `INSERT`/`UPDATE`/`DELETE`/`DROP`/`TRUNCATE`/`ALTER`/`CREATE`/`CALL` 全部拒绝
- [ ] 多语句 `SELECT 1; DROP TABLE rental;` 拒绝

**危险函数（AST 层面，这是重点）**
- [ ] `SELECT LOAD_FILE('/etc/passwd')` 拒绝
- [ ] `SELECT SLEEP(10)` 拒绝
- [ ] `SELECT BENCHMARK(1000000, MD5('a'))` 拒绝
- [ ] **`SELECT LOAD_FILE/**/('/etc/passwd')` 也必须拒绝**（函数名和括号之间插注释——这是正则方案的经典绕过口，AST 方案必须能拦住）
- [ ] **`SELECT 'LOAD_FILE() 这只是一段说明文字' AS note` 必须放行**（字符串字面量里出现危险函数名不算调用——这是正则方案的经典误伤，AST 方案必须不误伤）
- [ ] 嵌套在参数里的危险函数 `SELECT CONCAT('x', LOAD_FILE('/etc/passwd'))` 拒绝

**文件写出 / 锁**
- [ ] `SELECT * FROM rental INTO OUTFILE '/tmp/x'` 拒绝
- [ ] `SELECT * FROM rental FOR UPDATE` 拒绝

**查询形状**
- [ ] JOIN 数量超过 `max-joins`（默认 3）拒绝
- [ ] `CROSS JOIN` 拒绝
- [ ] 无 ON 条件的 JOIN 拒绝
- [ ] `LIMIT 10 OFFSET 100` 但没有 `ORDER BY` → 拒绝（分页结果不稳定）
- [ ] 子查询/CTE/UNION 分支**内部**的 JOIN 数量和危险函数也要被检查到（写一个把危险函数藏在三层子查询里的用例）

**LIMIT 处理**
- [ ] 没写 `LIMIT` 的查询，`safeSql` 里自动补上 `LIMIT <maxRows>`
- [ ] `LIMIT 100000` 超过阈值时，`safeSql` 里被下调为 `LIMIT <maxRows>`
- [ ] `LIMIT 5`（小于阈值）保持原样不动

**Fail-closed**
- [ ] 语法完全无法解析的字符串（`SELECT FROM WHERE`）拒绝，不放行
- [ ] 校验过程中抛出任何非预期异常时，结果是"拒绝"而不是"通过"（写一个用例：故意传一个能让解析器抛异常的畸形输入，断言 `valid == false`）

## 3. 为什么必须是 AST，不能是正则（实现前先理解）

这一段写进 `SqlSafetyGuard` 的类 Javadoc，因为它解释了整个类为什么长这样：

> 正则方案在这个场景下从原理上就堵不住两头：
> - **假阴性**：MySQL 允许在函数名和左括号之间插注释，`LOAD_FILE/**/('...')` 是合法调用但匹配不上 `LOAD_FILE\s*\(`。类似的绕过还有大小写混排、多余空白、反引号包裹。
> - **假阳性**：`SELECT '这是 LOAD_FILE() 的用法说明' AS doc` 里的危险函数名只是一段字符串字面量，正则会把一条完全无害的查询拦下来。
>
> AST 从根上消掉这两类问题：解析器已经把注释和空白规范化掉了，`Function` 节点就是真正的函数调用，`StringValue` 节点里的文本永远不会被当成函数。这不是"AST 比正则更严格"，是**两者判断的根本不是同一个东西**——正则判断的是字符序列，AST 判断的是语法结构。
>
> 文本预扫（第 4.1 节第 ① 步）仍然保留，但它的定位是"廉价的第一道粗筛"，不是安全边界本身；它误伤了也没关系（模型会重写），漏了也没关系（后面的 AST 检查兜底）。

## 4. `SqlSafetyGuard` 实现

### 4.1 校验流水线（七步，任一步不过立即返回拒绝）

```java
public ValidationResult validate(String sql) {
    try {
        // ① 文本预扫：廉价粗筛，命中直接拒。定位是"快速失败"，不是安全边界
        //    模式：INTO OUTFILE / INTO DUMPFILE / LOAD_FILE( / SYSTEM_USER( / @@ (系统变量)
        // ② 解析：Statements stmts = CCJSqlParserUtil.parseStatements(sql)
        //    stmts.size() != 1 → 拒绝（多语句注入）
        // ③ 语句类型白名单：只有 Select（含 WITH/CTE）放行，其余一律拒
        // ④ AST 遍历检查危险函数（见 4.2）
        // ⑤ 拦截 FOR UPDATE / SELECT INTO
        // ⑥ 查询形状校验，必须递归进 CTE/UNION/子查询/JOIN 的 ON 里（见 4.3）
        // ⑦ LIMIT 规范化，产出 safeSql（见 4.4）
        return ValidationResult.pass(safeSql);
    } catch (Exception anything) {
        // fail-closed：解析器抛什么都算拒绝。宁可让模型重写一条 SQL，
        // 也不放行任何我们没能完整理解的查询
        return ValidationResult.reject("SQL 无法被安全解析：" + anything.getMessage());
    }
}
```

### 4.2 危险函数检测：`DangerousFunctionFinder`

继承 JSqlParser 的 `TablesNamesFinder` 而不是从零实现 Visitor——它已经带了一整套完整的 AST 递归遍历逻辑（本来是用来提取表名的），我们只需要在函数节点上加一个钩子：

```java
/**
 * 复用 TablesNamesFinder 现成的全量递归遍历能力，只重写函数节点的访问。
 * 从零写一个 Visitor 意味着要自己处理 CTE、UNION、子查询、CASE WHEN、
 * 窗口函数等所有节点类型的递归——漏掉任何一种就是一个绕过口。
 */
final class DangerousFunctionFinder extends TablesNamesFinder {
    private static final Set<String> BLOCKED = Set.of(
            "load_file", "sleep", "benchmark", "system_user", "user", "database",
            "version", "current_user", "session_user", "master_pos_wait",
            "extractvalue", "updatexml");   // 后两个是经典的报错注入载体
    private final List<String> hits = new ArrayList<>();

    @Override
    public void visit(Function function) {
        String name = function.getName().toLowerCase(Locale.ROOT);
        if (BLOCKED.contains(name)) hits.add(name);
        super.visit(function);   // 必须调 super：参数里可能还嵌着函数调用
    }
}
```

> **`super.visit(function)` 这一行不能省**——不调的话 `CONCAT('x', LOAD_FILE(...))` 这种嵌套调用就检查不到，验收标准里有专门的用例覆盖它。
>
> 方法签名以第 1 节验证出的 JSqlParser 版本为准（不同大版本 Visitor 接口不同），**不要照抄这段伪代码的签名**。

### 4.3 查询形状校验（必须递归）

要检查的四项：
1. JOIN 总数 ≤ `maxJoins`（默认 3）
2. 没有 `CROSS JOIN`
3. 每个 JOIN 都有 `ON` 或 `USING`（无条件 JOIN 会产生笛卡尔积）
4. 有 `OFFSET` 就必须有 `ORDER BY`（否则分页结果不稳定，翻页会重复/漏行）

**递归是这一节的关键**：这四项检查必须下沉到每一个 `PlainSelect`，包括：
- CTE（`WITH x AS (...)`）里的每个子查询
- `UNION`/`UNION ALL` 的每个分支
- `FROM` 子句里的子查询（`FROM (SELECT ...) t`）
- `WHERE`/`HAVING` 里的子查询（`WHERE id IN (SELECT ...)`）
- JOIN 的 `ON` 条件里的子查询

写一个 `walk(Select)` 递归方法统一处理，**不要**只检查最外层的 `PlainSelect`——把危险的东西藏进子查询是最容易想到的绕过方式。验收标准里有专门用例。

JOIN 计数用"整棵树累加"还是"单层最大值"？**用整棵树累加**——一条查询在三个子查询里各 JOIN 3 张表，总代价和一层 JOIN 9 张表是同一个量级。

### 4.4 LIMIT 规范化

```java
// 三种情况：
// - 没有 LIMIT       → 加上 LIMIT maxRows
// - LIMIT > maxRows  → 下调到 maxRows（不是拒绝，是静默收窄。模型不需要为这件事重写 SQL）
// - LIMIT <= maxRows → 原样保留
```

规范化必须在 **AST 上改**然后 `toString()` 回来，不要用字符串拼接 `sql + " LIMIT 200"`——原 SQL 末尾可能有注释、分号、或者本来就带 `LIMIT`，字符串拼接会产出语法错误的 SQL。

`UNION` 的情况要注意：`LIMIT` 应该加在整个 `SetOperationList` 上，不是加在最后一个分支上（语义不同）。

### 4.5 `ValidationResult`

```java
/**
 * @param valid    是否通过
 * @param reason   拒绝原因。必须精确到具体规则（"JOIN 数量 5 超过上限 3"），
 *                 不能是笼统的"校验失败"——模型拿到具体原因才能自洽重写，
 *                 拿到笼统失败只会原地重试同一条 SQL
 * @param safeSql  通过时是 LIMIT 规范化后的 SQL；拒绝时为 null
 */
public record ValidationResult(boolean valid, String reason, String safeSql) {
    public static ValidationResult pass(String safeSql) { ... }
    public static ValidationResult reject(String reason) { ... }
}
```

拒绝文案清单（每条对应一种规则，实现时逐条落地）：

| 规则 | 文案 |
|---|---|
| 多语句 | `只允许单条 SQL 语句，检测到 N 条` |
| 非 SELECT | `只允许 SELECT/WITH 查询，检测到 <类型>` |
| 危险函数 | `检测到禁止的函数：<函数名>` |
| INTO OUTFILE | `禁止 INTO OUTFILE/DUMPFILE（文件写出）` |
| FOR UPDATE | `禁止 FOR UPDATE（加锁查询）` |
| JOIN 超限 | `JOIN 数量 N 超过上限 M，请拆成多次查询或先聚合` |
| CROSS JOIN | `禁止 CROSS JOIN（笛卡尔积）` |
| 无条件 JOIN | `JOIN 缺少 ON 条件，会产生笛卡尔积` |
| OFFSET 无 ORDER BY | `使用 OFFSET 分页时必须带 ORDER BY，否则翻页结果不稳定` |
| 解析失败 | `SQL 无法被安全解析：<原因>` |

## 5. 新增文件清单

| 文件 | 职责 |
|---|---|
| `capability/analytics/sql/ValidationResult.java` | record，见 4.5 |
| `capability/analytics/sql/SqlSafetyGuard.java` | 校验器主体。**不持有 `DataSource`**，构造参数只有配置（`maxJoins`/`maxRows`） |
| `capability/analytics/sql/DangerousFunctionFinder.java` | 包私有，见 4.2 |
| `capability/analytics/tools/ValidateSqlTool.java` | 产出 `ToolCallback`，名字 `validate_sql` |
| `config/AnalyticsSqlProperties.java` | `@ConfigurationProperties("agenttrail.analytics.sql")`：`maxJoins`（默认 3）、`maxRows`（默认 200，和 Ticket 6 的 `datasource.max-rows` 保持一致，**取同一个值不要配两处**） |

## 6. `validate_sql` 工具

```java
new JsonToolCallback("validate_sql", """
        在真正执行前预检查一条 SQL 是否安全合规，只校验不执行，不消耗数据库资源。

        建议在生成完一条较复杂的 SQL 后先调用它——校验失败时会告诉你具体违反了哪条规则，
        据此修改比直接执行后拿到一个数据库报错更快。

        注意：execute_sql 内部也会跑同一套校验，所以跳过这一步不会带来安全问题，
        只是会浪费一次往返。
        """,
        """
        {"type":"object","properties":{\
        "sql":{"type":"string","description":"【必填】要校验的 SQL 查询语句"}},\
        "required":["sql"]}""",
        args -> {
            String sql = args.text("sql");
            if (sql == null || sql.isBlank()) return "Error: 缺少必填参数 sql";
            ValidationResult result = guard.validate(sql);
            return result.valid()
                    ? "校验通过。实际将执行：\n" + result.safeSql()
                    : "校验未通过：" + result.reason();
        });
```

工具描述里那句"跳过这一步不会带来安全问题"是刻意写的——**不要**写成"必须先调用 validate_sql 才能执行"。这是 ReAct 不是 Workflow，模型不可能每次都遵守；把安全性寄托在提示词上是错的，真正的保证是 Ticket 11 里 `execute_sql` 无条件重跑校验。给模型一个诚实的说明，它反而会在真正需要时（复杂 SQL）用它。

## 7. 实现顺序

1. 第 1 节的 JSqlParser 版本验证（最小 Demo 跑通再往下）
2. `ValidationResult` record
3. 按验收标准的分组逐组推进，**每组先写测试再写实现**：基础放行 → 语句类型 → 危险函数（含两个正则绕过/误伤用例）→ INTO OUTFILE/FOR UPDATE → 查询形状 → 递归子查询 → LIMIT 规范化 → fail-closed
4. `ValidateSqlTool` + 工具级测试

整票不需要数据库，全程纯单测。

## 8. 明确禁止事项

- **不要**用正则作为危险函数检测的主要手段（文本预扫只是粗筛，不能替代 AST 检查）
- **不要**只检查最外层 `PlainSelect`——必须递归进 CTE/UNION/子查询/JOIN ON
- **不要**在 `DangerousFunctionFinder.visit(Function)` 里省略 `super.visit(function)`
- **不要**用字符串拼接的方式加/改 `LIMIT`，必须改 AST
- **不要**在解析异常时放行（fail-closed 是这一票的核心原则，没有例外）
- **不要**让 `SqlSafetyGuard` 依赖 `DataSource` 或任何需要数据库连接的东西
- **不要**在拒绝文案里只写"校验失败"——必须精确到规则
- **不要**在这一票里做 EXPLAIN 预检查、权限改写、脱敏（分别属于 Ticket 11/10/11）
- 其余共享约束同 Ticket 6/7

## 9. 和现有代码的边界

**修改**：`pom.xml`（加 JSqlParser 依赖）、`application.yml`（追加 `agenttrail.analytics.sql` 配置块）。
**新增**：`capability/analytics/sql` 包 + 一个工具类。
**不碰**：任何现有 Java 文件。
