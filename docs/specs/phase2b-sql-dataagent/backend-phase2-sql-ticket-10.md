# Ticket 10／18：数据权限 AST 改写（DataScopeRewriter）— 技术开发文档

> GitHub issue: [#56](https://github.com/renjian-pro/AgentTrail/issues/56)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.6 节。`Blocked by` [Ticket 6](backend-phase2-sql-ticket-06.md)（需要 `rental`/`payment` 上的 `user_id`/`dept_id` 列）和 [Ticket 9](backend-phase2-sql-ticket-09.md)（共用同一个 JSqlParser 版本）。[Ticket 11](backend-phase2-sql-ticket-11.md) 强依赖这一票。
>
> **这是整个 Phase 2B 技术含金量最高、也最容易做错的一票。** 做错的后果不是"功能不好用"，是**越权数据静默泄漏**——查询照常成功、结果看起来正常，只是里面混进了这个用户本不该看到的行，没有任何报错会提醒你。

## 0. 范围边界

**这一票只做**：给定一条 SQL 和一个 `DataScopeContext`，产出一条注入了数据范围过滤条件的新 SQL。

**这一票不做**：
- 解析用户能看到哪些部门（Phase 2A 的 `DataScopeResolver` 已经做完了，**直接消费它的产出，不要重写**）
- 执行 SQL（Ticket 11）
- 安全校验（Ticket 9 已完成，`execute_sql` 里的调用顺序是"先校验、再改写"，见 Ticket 11）
- 脱敏（Ticket 11）

## 1. 前置事实：Phase 2A 已经给了什么（先读代码，不要重新设计）

`src/main/java/com/agenttrail/sys/datascope/` 下已有：

```java
// DataScope.java —— 枚举：ALL / DEPT_AND_SUB / DEPT / SELF，带 compareByWidth 比较宽窄
// DataScopeContext.java
public record DataScopeContext(Long userId, DataScope scope, List<Long> deptIds) { ... }
// DataScopeResolver.java
@Service public class DataScopeResolver {
    public DataScopeContext resolve(Long userId) { ... }   // 已实现，含 fail-closed
}
```

`DataScopeResolver.resolve()` 的语义（`DataScopeContext` 的 Javadoc 已写明，这一票必须严格按它消费）：

| `scope` | `deptIds` | 这一票要怎么处理 |
|---|---|---|
| `ALL` | 空 | **不注入任何条件**，原样返回 SQL |
| `SELF` | 空（刻意的） | 按业务表的 `user_id` 列过滤成 `= <当前 userId>` |
| `DEPT` | 用户挂载的部门 | 按业务表的 `dept_id` 列过滤成 `IN (deptIds)` |
| `DEPT_AND_SUB` | 挂载部门 + 全部子孙部门（已展开） | 同 `DEPT`——**子树已经在 Resolver 里展开好了，这一票不需要再查部门树** |

> 注意 `SELF` 的 `deptIds` 是空列表，这不是 bug 也不是"没解析出来"，是约定：`SELF` 根本不看部门维度。实现时如果写成"deptIds 为空就拒绝"，`SELF` 用户会全部被误拦。

## 2. 验收标准

**基础**
- [ ] `scope = ALL`：SQL 原样返回，一个字符都不改
- [ ] 非 `SELECT` 语句：原样返回（安全校验是 Ticket 9 的职责，这里不重复拦）
- [ ] `scope = DEPT`，查 `rental`：注入 `rental.dept_id IN (...)`
- [ ] `scope = DEPT_AND_SUB`：注入的 `IN` 列表包含子部门 id（用 Ticket 06 种子数据里的 `mgr_test` 验证）
- [ ] `scope = SELF`：注入 `rental.user_id = <userId>`，**不注入 dept_id 条件**
- [ ] 没有配权限规则的表（`film`/`category` 等主数据）：不注入任何条件，查询正常返回全部行

**运算符优先级（核心，必须有专门用例）**
- [ ] 原 SQL 是 `WHERE status = 1 OR amount > 100`，改写后**原有的 OR 表达式必须被整体括起来**，形如 `WHERE (status = 1 OR amount > 100) AND rental.dept_id IN (...)`
- [ ] 写一个**行为级**（不是文本级）断言：构造两个部门的数据，用 `OR` 条件查询，断言改写后的结果集里**不含**其它部门的行

**LEFT JOIN（核心，必须有专门用例）**
- [ ] `customer c LEFT JOIN rental r ON ...` 时，`rental` 的权限条件必须进 **`ON` 子句**，不进 `WHERE`
- [ ] 行为级断言：`SELECT COUNT(*) FROM customer c LEFT JOIN rental r ON r.customer_id = c.customer_id` 改写前后**客户数不变**（sakila 是 599），没有可见租赁的客户仍然在结果里、对应列为 NULL——而不是退化成只剩有可见租赁的那部分客户
- [ ] `INNER JOIN` 的右表条件放 `WHERE`（不是 `ON`），行为上等价，选 `WHERE` 是为了让 SQL 更易读

**递归覆盖**
- [ ] `FROM (SELECT ... FROM rental) t` 子查询内部的 `rental` 也被注入条件
- [ ] `WHERE id IN (SELECT ... FROM payment)` 子查询内部的 `payment` 也被注入
- [ ] `WITH x AS (SELECT ... FROM rental) SELECT * FROM x`：CTE 定义里的 `rental` 被注入，**引用 CTE 的 `x` 不被当成真实表处理**（关键：CTE 别名不是表，对它注入 `x.dept_id` 会直接语法错误）
- [ ] `UNION` 的每个分支各自被注入

**别名**
- [ ] `SELECT * FROM rental r WHERE ...` → 注入的条件用别名 `r.dept_id`，不是 `rental.dept_id`（多表 JOIN 时不加限定会报字段歧义）
- [ ] 同一张表 self-join 两次用不同别名 → 两个别名各自被注入

**未注册的表（fail-closed）**
- [ ] 查一张既没配业务规则、也没登记进主数据白名单的表 → 抛 `DataScopeRewriteException`，**不是**静默放行（见 5.2）
- [ ] `user_profile` 走 `DefaultPermissionRule`，DEPT 档按它自己的 `dept_id` 列过滤（Ticket 06 冗余进来的那一列）
- [ ] `dim_dept` 登记为主数据，查它不注入任何条件（各档 scope 的用户看到的部门列表相同）

**Fail-closed**
- [ ] `DataScopeContext` 为 null → 抛 `DataScopeRewriteException`，不返回原 SQL
- [ ] `scope` 是 `DEPT`/`DEPT_AND_SUB` 但 `deptIds` 为空 → 抛异常（这意味着用户没挂任何部门，**不能理解成"不加条件"**）
- [ ] AST 解析或改写过程中抛任何异常 → 往外抛，**绝不返回未改写的原 SQL**

## 3. 为什么不能字符串拼接（实现前必须理解，写进类 Javadoc）

### 3.1 运算符优先级陷阱 —— 会直接导致数据泄漏

```sql
-- 原 SQL（模型生成）
SELECT * FROM rental WHERE status = 1 OR amount > 100

-- 字符串拼接： sql + " AND dept_id IN (10, 11)"
SELECT * FROM rental WHERE status = 1 OR amount > 100 AND dept_id IN (10, 11)
```

因为 `AND` 优先级高于 `OR`，这条 SQL 实际被解析成：

```sql
WHERE status = 1 OR (amount > 100 AND dept_id IN (10, 11))
```

**`status = 1` 这一整支完全不受部门过滤约束**——任何部门的、只要 `status = 1` 的行都会被返回。查询成功、没有任何报错、结果看起来很正常，但权限已经被绕过了。

AST 方案天然免疫：把原 WHERE 表达式作为一个整体节点，和新条件组合成 `AndExpression(Parenthesis(原表达式), 新条件)`，括号是在语法树层面确定的，不依赖任何优先级规则。

### 3.2 LEFT JOIN 语义破坏 —— 结果集悄悄变少

真实场景："列出所有客户，以及他们各自的租赁次数"——没租过的客户也要出现在列表里（次数为 0），这正是 `LEFT JOIN` 的用途：

```sql
SELECT c.customer_id, c.first_name, COUNT(r.rental_id) AS times
FROM customer c LEFT JOIN rental r ON r.customer_id = c.customer_id
GROUP BY c.customer_id, c.first_name
```

`customer` 是主数据（不注入条件），`rental` 有 `dept_id`（要注入）。如果把 `rental` 的权限条件放进 `WHERE`：

```sql
... WHERE r.dept_id IN (3, 4)
```

没有租赁记录的客户，`r.dept_id` 是 `NULL`，`NULL IN (...)` 结果是 `NULL`（不为真）→ 这些客户整行被过滤掉。**`LEFT JOIN` 静默退化成了 `INNER JOIN`**。这不是"权限收紧"，是查询语义被破坏——用户问的是"所有客户及其租赁次数"，拿到的却是"有租赁记录的客户"，599 个客户可能只剩下几百个。

同样没有报错，只是结果少了。这类 bug 在生产上极难被发现，因为没人知道"本来应该有多少行"。

正确做法：`LEFT JOIN` 右表的权限条件放进那个 JOIN 自己的 `ON` 子句——`ON r.customer_id = c.customer_id AND r.dept_id IN (3, 4)`，不匹配的行仍然以 NULL 形式保留左表数据，次数正确地算成 0。

### 3.3 关于多对多：本期不需要 EXISTS，但要知道为什么

一个常见场景是"权限依据在另一张多对多关联表里"（比如用户-部门中间表）。这时**不能用 JOIN 注入条件**：

```sql
-- 反例：JOIN 会改变结果集行数
FROM some_table t JOIN t_dept_rel rel ON rel.entity_id = t.id AND rel.dept_id IN (3, 4)
```

一个关联了两个可见部门的实体会**在结果里出现两行**。正确做法是 `EXISTS`——只判断"存在与否"，不改变行数：

```sql
WHERE EXISTS (SELECT 1 FROM t_dept_rel rel WHERE rel.entity_id = t.id AND rel.dept_id IN (3, 4))
```

**但本期授权范围内没有这样的表**：`rental`/`payment`/`user_profile` 都有直接的 `dept_id` 列（`user_profile` 的那一列是 Ticket 06 第 3.3 节刻意冗余进来的，正是因为分析账号读不到 `sys_user_dept`）。所以：

- **不要**为了演示这个技巧去实现一个没有对应表的规则
- **要**把上面这段写进 `PermissionRule` 接口的 Javadoc——未来真的加了多对多的业务表时，实现者知道该走 `EXISTS` 而不是 JOIN

## 4. 新增文件清单

包根：`com.agenttrail.capability.analytics.permission`

| 文件 | 职责 |
|---|---|
| `PermissionRule.java` | 接口：`Set<String> managedTables()` + `Expression buildCondition(Table table, DataScopeContext ctx)`。**返回 JSqlParser 的 `Expression` 节点，不是 String** |
| `PermissionRuleRegistry.java` | 表名（小写）→ 规则 的路由。**未注册的表名抛异常，不做兜底**（fail-closed，理由见 5.2） |
| `DefaultPermissionRule.java` | 适用于同时有 `user_id` 和 `dept_id` 两列的表——本期覆盖 `rental`/`payment`/`user_profile` 全部三张。见 5.1 |
| `NoPermissionRule.java` | 显式的"这张表不注入任何条件"规则（`buildCondition` 返回 null），给 `film`/`category`/`customer`/`inventory`/`dim_dept` 这类主数据用。**用它而不是"注册表里查不到就跳过"**——显式声明比隐式默认更安全，新表加进来时会强制做一次决策 |
| `DataScopeRewriter.java` | 主体：AST 遍历 + 条件注入。见第 6 节 |
| `DataScopeRewriteException.java` | 运行时异常，改写失败时抛 |

## 5. 两个内置规则

### 5.1 `DefaultPermissionRule`（rental / payment / user_profile）

```java
public Expression buildCondition(Table table, DataScopeContext ctx) {
    // qualifier：有别名用别名，没别名用表名。多表 JOIN 时不加限定会报 "Column 'dept_id' in
    // where clause is ambiguous"——两张表都有 dept_id 列时必然踩到
    String qualifier = table.getAlias() != null ? table.getAlias().getName() : table.getName();
    return switch (ctx.scope()) {
        case ALL -> null;                                    // 调用方据此跳过注入
        case SELF -> eq(qualifier + ".user_id", ctx.userId());
        case DEPT, DEPT_AND_SUB -> in(qualifier + ".dept_id", ctx.deptIds());
    };
}
```

`DEPT` 和 `DEPT_AND_SUB` 走同一个分支不是偷懒——子树展开已经在 `DataScopeResolver` 里做完了，到这里两者的差别只体现在 `deptIds` 列表长度上。

`user_profile` 也走这条规则，因为 Ticket 06 第 3.3 节给它冗余了 `dept_id` 列——**这正是那个冗余设计换来的收益**：三张有归属的表用同一条规则，不需要为"部门关系在 sys_user_dept 里"写特殊逻辑（而且按 Ticket 06 第 5 节的授权设计，分析账号根本读不到 `sys_*` 任何一张表，想写也写不了）。

### 5.2 `NoPermissionRule`（主数据表）

`film`/`film_actor`/`film_category`/`film_text`/`category`/`language`/`actor`/`customer`/`address`/`city`/`country`/`inventory`/`dim_dept` 这些是主数据，不属于任何部门，`buildCondition` 直接返回 null。

`dim_dept`（部门维度）也在这里：部门名称本身不是机密，谁都该能看到组织结构；真正受控的是"哪些部门的业绩数据能看"，那由 `rental.dept_id` 上的条件保证。

**必须在 `PermissionRuleRegistry` 里显式注册它们**，而不是靠"查不到规则就跳过"这种隐式默认。理由：将来往分析库加一张新的业务表时，如果注册表里没有它，开发者会在写测试时立刻发现"这张表没配规则"，被迫做一次显式决策；隐式跳过则会静默地让新表完全不受权限约束——一个悄无声息的越权口子。

所以 `PermissionRuleRegistry.ruleFor(tableName)` 对**完全没注册过**的表名，应该抛异常或返回一个"拒绝"标记，而不是返回 `NoPermissionRule`。这一条写一个测试。

### 5.3 构造 `Expression` 的方式

不要手工 `new AndExpression(...)` 一层层拼——太啰嗦且容易写错。用 `CCJSqlParserUtil.parseCondExpression(String)` 把一小段条件文本解析成 `Expression` 节点：

```java
private static Expression cond(String sqlFragment) {
    try {
        return CCJSqlParserUtil.parseCondExpression(sqlFragment);
    } catch (JSQLParserException e) {
        throw new DataScopeRewriteException("权限条件构造失败: " + sqlFragment, e);
    }
}
```

**注意**：`deptIds` 和 `userId` 都是从数据库解析出来的 `Long`，不是用户输入，拼进片段里没有注入风险。但仍然要做一次防御性断言（`deptIds` 全部非 null 且是 `Long`），避免未来有人把这个方法拿去拼别的东西。

## 6. `DataScopeRewriter` 主体

### 6.1 入口

```java
public String rewrite(String sql, DataScopeContext ctx) {
    if (ctx == null) throw new DataScopeRewriteException("DataScopeContext 为空，拒绝执行");
    if (ctx.scope() == DataScope.ALL) return sql;              // 唯一的原样返回分支
    if (ctx.scope() != DataScope.SELF && ctx.deptIds().isEmpty()) {
        // DEPT/DEPT_AND_SUB 但一个部门都没有 = 这个用户什么都不该看到。
        // 绝不能理解成"不加条件"——那是从"什么都看不到"翻转成"什么都能看到"
        throw new DataScopeRewriteException("用户无任何可见部门，拒绝执行: userId=" + ctx.userId());
    }
    try {
        Statement stmt = CCJSqlParserUtil.parse(sql);
        if (!(stmt instanceof Select select)) return sql;      // 非 SELECT 交给 Ticket 9 拦
        walk(select, ctx);
        return stmt.toString();
    } catch (DataScopeRewriteException e) {
        throw e;
    } catch (Exception anything) {
        // fail-closed：改写没成功就绝不放行原 SQL
        throw new DataScopeRewriteException("权限改写失败，拒绝执行: " + anything.getMessage(), anything);
    }
}
```

### 6.2 递归遍历 `walk`

要处理的节点类型（漏一种就是一个越权口子）：

| 节点 | 处理 |
|---|---|
| `PlainSelect` | ① 收集 CTE 别名（见 6.3）② 对 `FROM` 主表和每个 JOIN 的表注入条件 ③ 递归 `FROM` 里的子查询 ④ 递归 `WHERE`/`HAVING` 里的子查询 ⑤ 递归 JOIN `ON` 里的子查询 |
| `SetOperationList`（UNION 等） | 每个分支各自 `walk` |
| `ParenthesedSelect` | 剥掉括号继续 `walk` |
| `WithItem`（CTE 定义） | `walk` 它的 select 体 |

### 6.3 CTE 别名必须排除（最容易漏的一条）

```sql
WITH recent AS (SELECT * FROM rental WHERE ...)
SELECT * FROM recent
```

外层 `FROM recent` 里的 `recent` **不是一张真实的表**，是 CTE 别名。对它注入 `recent.dept_id IN (...)` 会：
- 如果 CTE 的 SELECT 列表里没带 `dept_id` → 直接语法错误，查询报错
- 如果带了 → 条件被重复应用两次（CTE 内部已经注入过一次）

实现：`walk` 一个 `PlainSelect` 之前，先把当前作用域可见的所有 CTE 名字收集进一个 `Set<String>`，注入时跳过名字在这个集合里的"表"。CTE 内部的真实表照常注入。

### 6.4 注入位置的判断（LEFT JOIN 的核心逻辑）

```java
private void injectInto(PlainSelect select, DataScopeContext ctx, Set<String> cteNames) {
    // FROM 主表 → WHERE
    if (select.getFromItem() instanceof Table t && !cteNames.contains(lower(t.getName()))) {
        Expression cond = registry.ruleFor(t.getName()).buildCondition(t, ctx);
        if (cond != null) andIntoWhere(select, cond);
    }
    for (Join join : nullSafe(select.getJoins())) {
        if (!(join.getRightItem() instanceof Table t)) continue;   // 子查询由 walk 递归处理
        if (cteNames.contains(lower(t.getName()))) continue;
        Expression cond = registry.ruleFor(t.getName()).buildCondition(t, ctx);
        if (cond == null) continue;
        if (join.isLeft() || join.isRight() || join.isFull()) {
            // 外连接：条件必须进 ON，进 WHERE 会把外连接退化成内连接（见 3.2）
            andIntoJoinOn(join, cond);
        } else {
            andIntoWhere(select, cond);
        }
    }
}
```

> `RIGHT JOIN` 和 `FULL JOIN` 和 `LEFT JOIN` 是同一类问题，一起处理。项目里大概率不会出现它们（Ticket 9 也没禁），但少写一个分支就是少一个保护。

### 6.5 `andIntoWhere` —— 括号是安全边界

```java
private static void andIntoWhere(PlainSelect select, Expression cond) {
    Expression existing = select.getWhere();
    if (existing == null) {
        select.setWhere(cond);
        return;
    }
    // 原表达式必须整体括起来。不加括号时，原 WHERE 里的 OR 会和新加的 AND
    // 按优先级重新结合，权限条件被 OR 短路（见 3.1，这是数据泄漏级的 bug）
    select.setWhere(new AndExpression(new Parenthesis(existing), cond));
}
```

**即使原表达式里没有 `OR` 也要加括号**——不要写"检测到 OR 才加括号"这种优化。多余的括号零成本，漏加一次就是泄漏；而且"什么情况下算有 OR"本身要递归判断，比无脑加括号复杂得多。

`andIntoJoinOn` 同理：`join.getOnExpressions()` 拿到现有条件，包 `Parenthesis` 后和新条件组成 `AndExpression`，再 `setOnExpressions`。

## 7. 实现顺序

1. `PermissionRule` 接口 + `NoPermissionRule` + `PermissionRuleRegistry`（先把路由骨架搭起来，单测：注册/命中/未命中兜底）
2. `DefaultPermissionRule`，纯单测（给一个 `Table` 和 `DataScopeContext`，断言产出的 `Expression.toString()` 文本）
3. `DataScopeRewriter` 的 `ALL` 分支 + fail-closed 三个分支（最简单，先绿）
4. 单表 `WHERE` 注入 + **括号用例**（第 2 节"运算符优先级"两条）
5. JOIN 注入 + **LEFT JOIN 行为级用例**（第 2 节"LEFT JOIN"两条）——这一步用 Ticket 06 的 `AnalyticsLocalDbTestSupport` 真跑 SQL 断言行数，不能只断言文本
6. 递归：子查询 → CTE（含别名排除）→ UNION
7. 别名限定用例（self-join 两个别名）
8. `NoPermissionRule` 主数据白名单 + 未注册表抛异常的 fail-closed 用例

**第 4/5 步的行为级测试是这一票的核心价值**，不要用"断言生成的 SQL 文本长什么样"代替——文本对了不代表语义对了，LEFT JOIN 那个坑恰恰是"文本看起来完全合理"的。

## 8. 明确禁止事项

- **不要**用字符串拼接注入条件（`sql + " AND dept_id IN ..."`），必须改 AST
- **不要**在 `andIntoWhere` 里省略 `Parenthesis`，也不要写"有 OR 才加括号"的条件判断
- **不要**把外连接右表的条件放进 `WHERE`
- **不要**为了演示 `EXISTS` 技巧去实现一个没有对应表的规则（见 3.3）——但要把那段理由写进接口 Javadoc
- **不要**给未注册的表做"兜底放行"，必须抛异常
- **不要**对 CTE 别名注入条件
- **不要**在改写失败/异常时返回原 SQL——必须抛异常（fail-closed）
- **不要**把 `deptIds` 为空当成"不加条件"
- **不要**重新实现部门树查询或 scope 合并逻辑——`DataScopeResolver`（Phase 2A）已经做完了，直接消费
- **不要**只写文本断言测试，`OR` 优先级和 `LEFT JOIN` 两处必须有行为级（真跑 SQL 比行数）的测试
- 其余共享约束同 Ticket 6/7

## 9. 和现有代码的边界

**新增**：`capability/analytics/permission` 包。
**依赖（只读消费，不修改）**：`com.agenttrail.sys.datascope.DataScopeResolver` / `DataScopeContext` / `DataScope`。
**不碰**：`com.agenttrail.sys` 下任何文件、`db/schema.sql`、Ticket 9 的 `SqlSafetyGuard`。
