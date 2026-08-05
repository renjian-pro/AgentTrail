# Ticket 07／18：M-Schema 自省 + 缓存 + Schema 两工具 — 技术开发文档

> GitHub issue: [#53](https://github.com/renjian-pro/AgentTrail/issues/53)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.1／5.2／5.12 节。`Blocked by` [Ticket 6](backend-phase2-sql-ticket-06.md)（需要 `analyticsDataSource`）。
>
> 这一票产出 DataAgent 的**第一批工具**，所以顺带承担一件公共改造：把 `com.agenttrail.loop.tools` 里两个包私有的工具样板类提升为 public（第 2 节），后续 Ticket 8/9/11/12 的工具都依赖这一步。

## 0. 范围边界

**这一票只做**：数据库元数据自省 → `Mschema` 对象 → 紧凑文本格式化 → Redis 缓存（含多实例刷新保护）→ 两个工具（`list_tables`/`describe_tables`）。

**这一票不做**：
- 术语字典 / `lookup_glossary`（Ticket 8）
- 任何 SQL 校验、执行、改写（Ticket 9/10/11）
- 把工具挂进 `AgentLoopExecutor`（Ticket 12 统一装配）——这一票只产出 `ToolCallback` Bean，不接线到执行器

## 1. 验收标准

- [ ] `JsonToolCallback`/`ToolArguments` 提升为 public 后，`mvn test` 仍然全绿（回归验证没破坏现有三个内置工具）
- [ ] `MschemaIntrospector` 对改造后的 sakila 库自省，能取到全部表、每张表的列/类型/注释、主键、外键
- [ ] 自省结果**不包含**任何 `agent_*`/`sys_*` 表。注意这里有两层：主防线是 Ticket 06 第 5 节的按表授权（`analytics_ro` 压根看不到这些表，`DatabaseMetaData.getTables()` 里不会出现），`exclude-tables` 配置是第二层。**两层都要测**：一条断言自省结果里没有 `agent_session`，另一条用配置项排除一张确实有权限的表、断言通配符规则生效
- [ ] 字符串列的示例值采样有超时保护：整体采样超过配置的秒数后放弃剩余列，**已采到的保留**，自省整体不失败（写一个测试：把超时配成 1ms，断言自省仍然返回完整的表结构、只是示例值为空）
- [ ] 敏感字段（配置在 `mask-fields` 里的）**不被采样**——测试断言 `Mschema` 里这些列的示例值列表为空，且断言采样 SQL 压根没对这些列执行过
- [ ] `MschemaFormatter` 输出的文本里，每个字段是 `(字段名: 类型, 注释, Examples: [...])` 形态，外键单独成块
- [ ] `MschemaCacheService.get()` 三层兜底都有测试覆盖：① Redis 有值直接返回；② Redis 抛异常时降级到内存副本；③ 两者都没有时同步自省
- [ ] 多实例并发刷新保护：两个 `MschemaCacheService` 实例同时调 `refresh()`，只有一个真正执行自省，另一个跳过（用 `RedisTaskLock` 实现，测试连本机已有的 Redis 实例验证）
- [ ] `list_tables` 工具返回表名 + 表注释 + 关联表，**不返回字段详情**
- [ ] `describe_tables` 工具接收表名数组，返回这些表的完整字段详情
- [ ] `describe_tables` 传入不存在的表名时返回明确的错误文案（而不是空结果或异常）

## 2. 前置公共改造：把工具样板类提升为 public

现状（先读一遍确认）：
- `src/main/java/com/agenttrail/loop/tools/JsonToolCallback.java:21` 是 `final class`（包私有）
- `src/main/java/com/agenttrail/loop/tools/ToolArguments.java:15` 是 `final class`（包私有），方法 `text`/`integer`/`longValue`/`flag` 也都是包私有

DataAgent 的工具放在 `com.agenttrail.capability.analytics.tools` 包下（不是 `loop.tools`——`loop` 包只装 Runtime 通用机制，不含业务领域概念，见 `CONTEXT.md` 的边界定义），所以拿不到这两个包私有类。

**改法（改动最小，不要重写）**：

1. `JsonToolCallback`：`final class` → `public final class`，构造函数 `JsonToolCallback(...)` → `public JsonToolCallback(...)`。其它一行不动。
2. `ToolArguments`：`final class` → `public final class`，`parse`/`text`/`integer`/`longValue`/`flag` 五个方法加 `public`。
3. `ToolArguments` **新增**一个方法（`describe_tables` 需要读字符串数组）：

```java
/** 字符串数组入参；字段缺失、不是数组、或数组为空时统一返回空列表，由调用方自己判断"空了要怎么办"。 */
public List<String> textList(String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray()) {
        return List.of();
    }
    List<String> items = new ArrayList<>();
    for (JsonNode element : value) {
        String text = element.isTextual() ? element.textValue() : element.asText();
        if (text != null && !text.isBlank()) {
            items.add(text.trim());
        }
    }
    return List.copyOf(items);
}
```

**不要**顺手重构这两个类的其它部分，**不要**把它们挪包。改完立刻跑一次 `mvn test` 确认 `FileSystemTools`/`BashTool`/`GrepTool`/`FileContentTool` 全部没受影响。

## 3. 新增文件清单

包根：`com.agenttrail.capability.analytics`

| 文件 | 职责（只做这一件事） |
|---|---|
| `schema/Mschema.java` | 元数据模型 record：`Mschema(String database, List<TableDef> tables)`；嵌套 record `TableDef(String name, String comment, List<FieldDef> fields, List<ForeignKeyDef> foreignKeys)`、`FieldDef(String name, String type, String comment, boolean primaryKey, List<String> examples)`、`ForeignKeyDef(String fromColumn, String toTable, String toColumn)`。**必须能被 Jackson 序列化/反序列化**（要进 Redis），写一个 round-trip 测试 |
| `schema/MschemaIntrospector.java` | JDBC `DatabaseMetaData` 自省，产出 `Mschema`。见第 4 节 |
| `schema/MschemaFormatter.java` | `Mschema` → 紧凑文本。见第 5 节 |
| `schema/MschemaCacheService.java` | Redis 缓存 + 内存降级 + 定时刷新 + 多实例刷新锁。见第 6 节 |
| `schema/SchemaProvider.java` | 接口，两个方法：`String listTables()`、`String describeTables(List<String> tableNames)`。工具只依赖这个接口 |
| `schema/MschemaSchemaProvider.java` | `SchemaProvider` 的实现，内部调 `MschemaCacheService` + `MschemaFormatter` |
| `tools/ListTablesTool.java` | 产出 `ToolCallback`，名字 `list_tables` |
| `tools/DescribeTablesTool.java` | 产出 `ToolCallback`，名字 `describe_tables` |
| `config/AnalyticsSchemaProperties.java` | `@ConfigurationProperties("agenttrail.analytics.schema")`：`excludeTables`（List\<String\>，支持 `前缀*` 通配符）、`sampleTimeoutMs`、`sampleLimit`（每列取几个示例值，默认 5）、`cacheTtlHours`、`refreshCron` |
| `config/AnalyticsSchemaConfig.java` | `@Configuration`，把上面这些串起来注册成 Bean，条件同 Ticket 6 的 `@ConditionalOnProperty` |

## 4. `MschemaIntrospector` 实现细节

### 4.1 五步流程

```java
public Mschema introspect() {
    try (Connection conn = dataSource.getConnection()) {
        // ① catalog 必须从连接本身取，不能传 null。MySQL 下 catalog=null 会把服务器上
        //    所有 database 的表都捞回来——本地开发时主库和分析库常在同一个 MySQL 实例上，
        //    这会让 agenttrail 主库的 agent_session/sys_user 这些内部表直接暴露给模型
        String catalog = conn.getCatalog();
        DatabaseMetaData meta = conn.getMetaData();
        // ② 表清单：getTables(catalog, null, "%", new String[]{"TABLE"})
        //    只要 "TABLE"，不要 "VIEW"——Ticket 6 已经把视图删干净了，这里是第二道保险
        //    表注释取 REMARKS 列
        // ③ 列 + 主键：getColumns(...) 取 COLUMN_NAME/TYPE_NAME/COLUMN_SIZE/REMARKS，
        //    getPrimaryKeys(...) 取主键列名集合
        // ④ 外键：getImportedKeys(...) 取 FKCOLUMN_NAME/PKTABLE_NAME/PKCOLUMN_NAME
        // ⑤ 示例值采样：见 4.3
    }
}
```

### 4.2 表排除规则

`excludeTables` 支持两种写法，实现时都要支持：
- 精确名：`payment_backup`
- 前缀通配：`agent_*`（只支持结尾一个 `*`，不要实现完整正则/glob——需求只有这一种形态，多做是过度设计）

匹配上的表在第 ② 步就跳过，后续步骤不再处理。

### 4.3 示例值采样（这一节最容易做错，逐条对照）

```java
// 只对字符串类型列采样——数值/日期列的"示例值"对 SQL 生成几乎没有帮助，
// 反而占上下文。字符串列才有"这个字段实际存的是什么形态"这个信息价值
private static final Set<String> SAMPLEABLE_TYPES =
        Set.of("VARCHAR", "CHAR", "ENUM", "SET", "TINYTEXT", "TEXT", "MEDIUMTEXT");
```

三条硬约束：

1. **敏感字段跳过采样**：采样前先查一遍 `mask-fields` 名单（配置项和 Ticket 11 的 `SensitiveFilter` 共用同一份，见第 8 节），命中的列直接跳过。**理由是这比"采样后再脱敏"干净得多——敏感值从一开始就不会进 Redis 缓存**，不存在"缓存里躺着明文身份证"这个问题。
2. **异步 + 整体超时**：每列一个 `CompletableFuture`（`SELECT DISTINCT <col> FROM <table> WHERE <col> IS NOT NULL LIMIT <sampleLimit>`），最后 `CompletableFuture.allOf(...).get(sampleTimeoutMs, MILLISECONDS)`。**超时后不抛异常**——捕获 `TimeoutException`，把已经完成的 future 的结果收上来，没完成的当作"这一列没有示例值"。理由：示例值是锦上添花，一张几百万行的大表 `SELECT DISTINCT` 很慢，绝不能让它拖垮整个自省流程。
3. **示例值清洗**（对齐 M-Schema 规范，写一个独立的静态方法便于单测）：
   - 超过 50 字符的值直接丢弃
   - 超过 20 字符的值只保留 1 个
   - 长得像邮箱（含 `@`）或 URL（含 `://`）的值直接过滤
   - 日期时间形态的值只保留 1 个

### 4.4 采样查询用的连接

采样是 N 条独立查询并发跑，会同时占用 N 个连接。`sampleLimit` 默认 5、表数量几十张的情况下，并发度可能超过 Ticket 6 配的 `maximumPoolSize=10`。**实现时要给采样加一个并发度上限**（比如固定大小为 `maximumPoolSize / 2` 的 `Executor`），不要无脑 `CompletableFuture.supplyAsync` 用公共 ForkJoinPool 一次性打满连接池——否则自省期间正常的 `executeSql` 会拿不到连接。

## 5. `MschemaFormatter` 输出格式

```
# Database: sakila

## rental  -- 租赁记录
(rental_id: SMALLINT, 主键)
(rental_date: DATETIME, 租出时间)
(inventory_id: MEDIUMINT, 库存条目)
(customer_id: SMALLINT, 客户)
(user_id: BIGINT, 经手业务员，对应主库 sys_user.id)
(dept_id: BIGINT, 归属部门，对应 sys_dept.id)

## payment  -- 付款记录
...

# Foreign keys
rental.inventory_id -> inventory.inventory_id
rental.customer_id -> customer.customer_id
payment.rental_id -> rental.rental_id
```

要点：
- `list_tables` 只输出 `## 表名 -- 表注释` 这一层 + `# Foreign keys` 块（让模型能判断表之间怎么关联），**不输出字段行**
- `describe_tables` 输出指定表的完整字段行 + 这些表相关的外键
- 注释为空时不输出 `-- ` 那一段，不要输出 `-- null`
- 示例值非空时追加 `, Examples: [a, b, c]`，为空时整个片段不出现

格式化逻辑必须是纯函数（输入 `Mschema` 输出 `String`，不碰数据库），这样单测不需要容器。

## 6. `MschemaCacheService`（含多实例刷新保护）

### 6.1 三层兜底读取

```java
public Mschema get() {
    try {
        Mschema cached = readFromRedis();       // ① Redis
        if (cached != null) { this.fallback = cached; return cached; }
    } catch (RuntimeException redisDown) {
        log.warn("Redis 不可用，降级到内存副本: {}", redisDown.getMessage());
    }
    Mschema local = this.fallback;              // ② 内存副本（上一次成功读到的）
    if (local != null) { return local; }
    return refresh();                           // ③ 都没有，同步自省
}
```

`fallback` 用 `volatile Mschema` 字段，每次成功读到 Redis 都更新它。这样 Redis 挂掉时应用还能用上一份快照继续工作，而不是每个请求都去自省一次数据库。

### 6.2 多实例并发刷新保护（这是这一票唯一的分布式部分）

定时刷新（`@Scheduled(cron = "${agenttrail.analytics.schema.refresh-cron}")`）在多实例部署下会同时触发：N 个实例同时对分析库做全量自省 + 采样，既浪费又给数据库压力。

**复用项目已有的 `com.agenttrail.loop.task.RedisTaskLock`，不要引入新的分布式协调机制。** 这个类的构造是 `RedisTaskLock(RedissonClient redisson, String instanceId, Duration ttl)`，`tryAcquire(String key)` 就是 SETNX 语义：

```java
public Mschema refresh() {
    // key 用固定字符串（整个集群共用一把锁），不是 conversationId
    if (!refreshLock.tryAcquire(REFRESH_LOCK_KEY)) {
        log.debug("本轮 Schema 刷新已被其他实例抢到，跳过；下次 get() 会读到对方刷好的结果");
        return get();   // 直接读缓存，不自己刷
    }
    try {
        Mschema fresh = introspector.introspect();
        writeToRedis(fresh);
        this.fallback = fresh;
        return fresh;
    } finally {
        refreshLock.release(REFRESH_LOCK_KEY);
    }
}
```

锁的 TTL 给 **60 秒**就够（自省本身有采样超时兜底，不会跑很久），**不要开 `startAutoRenewal()`**——那是给长任务用的，Schema 刷新是短操作，锁意外没释放时让 TTL 自己过期就是正确行为。

> 注意第 ③ 层兜底和刷新锁的交互：`get()` 在缓存全空时会调 `refresh()`，`refresh()` 抢不到锁时又调 `get()`——**这会无限递归**。实现时必须打断这个环：抢不到锁的分支改成"读一次 Redis，读到就返回，读不到就自己不加锁地做一次自省"（宁可重复一次自省，也不能栈溢出）。这一条写一个专门的测试覆盖。

### 6.3 Redis key 和序列化

- key：`agenttrail:analytics:mschema:<database名>`，TTL 取 `cacheTtlHours`（默认 24 小时，比刷新周期长，保证刷新失败时旧值还在）
- 序列化：Jackson，直接存 JSON 字符串。`Mschema` 用 record + `@JsonCreator` 或确保 Jackson 的 record 支持已启用；写一个 round-trip 测试（序列化再反序列化后对象相等）

## 7. 两个工具的定义

工具描述文案是给模型看的，**质量直接决定模型会不会正确使用**，不要随便写。参照 `FileContentTool.toolCallback()`（`loop/tools/FileContentTool.java:30`）的写法。

### 7.1 `list_tables`

```java
public ToolCallback toolCallback() {
    return new JsonToolCallback("list_tables", """
            列出分析数据库里所有可查询的表，包含表名、业务含义和表之间的外键关联。

            这是数据分析的第一步：先用这个工具看清有哪些表、怎么关联，选出本次问题
            真正需要的少数几张表，再用 describe_tables 展开它们的字段详情。

            不要跳过这一步直接猜表名，也不要一次性把所有表都传给 describe_tables——
            那样会让上下文里塞满和当前问题无关的字段，反而更容易写错 SQL。
            """,
            """
            {"type":"object","properties":{},"required":[]}""",
            args -> schemaProvider.listTables());
}
```

`list_tables` 没有入参，`inputSchema` 就是一个空 properties 的 object，**不要省略这个字段**（部分模型对缺失 schema 的工具处理不一致）。

### 7.2 `describe_tables`

```java
new JsonToolCallback("describe_tables", """
        展开指定表的完整字段详情：字段名、类型、业务含义、主键标记、示例值，以及这些表相关的外键。

        table_names 从 list_tables 的结果里选，一次只传本次查询真正要用到的表（通常 1-4 张）。
        表名写错或表不存在时会明确告诉你，不要凭空猜字段名。
        """,
        """
        {"type":"object","properties":{\
        "table_names":{"type":"array","items":{"type":"string"},\
        "description":"【必填】要展开的表名列表，来自 list_tables 的结果"}},\
        "required":["table_names"]}""",
        args -> {
            List<String> names = args.textList("table_names");
            if (names.isEmpty()) {
                return "Error: 缺少必填参数 table_names，或传入的是空数组";
            }
            return schemaProvider.describeTables(names);
        });
```

`describeTables` 内部遇到不存在的表名：**不要静默忽略**，返回文案里明确列出哪些表名没找到 + 提示调 `list_tables` 看真实表名。理由同踩坑点 #24 的思路——给模型可操作的修复建议，而不是一个让它无从下手的空结果。

## 8. 配置项（追加到 `application.yml`）

```yaml
agenttrail:
  analytics:
    schema:
      exclude-tables: []            # 支持精确名和 "前缀*" 通配
      sample-limit: 5               # 每个字符串列取几个示例值
      sample-timeout-ms: 5000       # 整体采样超时；超时后已采到的保留，未完成的放弃
      cache-ttl-hours: 24
      refresh-cron: "0 0 3 * * *"   # 每天凌晨 3 点；多实例下只有抢到锁的那个真正执行
    # 这份名单同时被 Schema 采样（本票，跳过采样）和 SensitiveFilter（Ticket 11，结果脱敏）使用，
    # 是同一份配置不是两份——两处都要覆盖才算真正防住泄漏
    mask-fields:
      - user_profile.id_card
      - user_profile.home_address
```

## 9. 实现顺序

1. 第 2 节的可见性改造 + `textList` 方法，跑 `mvn test` 确认无回归
2. `Mschema` 数据模型 + Jackson round-trip 测试（不需要数据库，先做）
3. `MschemaFormatter`（纯函数）+ 单测（构造一个手写的 `Mschema` 断言输出文本，不需要容器）
4. `MschemaIntrospector`：基于 Ticket 06 的 `AnalyticsLocalDbTestSupport` 写集成测试，先测"能取到全部表和字段"，再测排除规则，最后测采样（超时、敏感字段跳过各一个用例）
5. `MschemaCacheService`：先测三层兜底，再测刷新锁（两个实例并发），**专门测一次 6.2 末尾提到的递归打断**
6. `MschemaSchemaProvider` + 两个工具 + 工具级测试（断言返回文本形态、断言错误分支文案）

## 10. 明确禁止事项

- **不要**把 `JsonToolCallback`/`ToolArguments` 挪包或重构其内部逻辑，只改可见性 + 加一个方法
- **不要**把 DataAgent 的工具放进 `com.agenttrail.loop.tools` 包（`loop` 只装 Runtime 通用机制，业务能力包走 `capability.analytics`）
- **不要**让示例值采样的失败/超时导致整个自省失败——采样是锦上添花
- **不要**对敏感字段采样后再脱敏，必须采样前就跳过
- **不要**在 `getTables` 时传 `catalog = null`（会跨 database 捞表）
- **不要**引入新的分布式锁库——刷新保护用已有的 `RedisTaskLock`
- **不要**给刷新锁开 `startAutoRenewal()`
- **不要**在这一票里把工具挂到 `AgentLoopExecutorFactory` 上（Ticket 12 统一做）
- 其余共享约束（集成测试连本机真实 MySQL/Redis、不用 H2、不用 Testcontainers、中文注释、不点名来源仓库）同 Ticket 06

## 11. 和现有代码的边界

**修改**：`loop/tools/JsonToolCallback.java`、`loop/tools/ToolArguments.java`（仅可见性 + 新增一个方法）、`application.yml`（追加配置块）。
**新增**：`com.agenttrail.capability.analytics.schema` / `.tools` / `.config` 三个包。
**不碰**：`AgentLoopExecutorFactory`、`AgentLoopController`、`com.agenttrail.sys.*`、`db/schema.sql`。
