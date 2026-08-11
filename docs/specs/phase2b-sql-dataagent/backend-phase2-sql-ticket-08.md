# Ticket 08／18：业务术语字典 + 时间锚点 + lookup_glossary 工具 — 技术开发文档

> GitHub issue: [#54](https://github.com/renjian-pro/AgentTrail/issues/54)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.3 节。`Blocked by` [Ticket 7](backend-phase2-sql-ticket-07.md)（需要 `JsonToolCallback` 已提升为 public）。**不依赖** Ticket 9/10/11，可以和它们并行做。
>
> 这是 Phase 2B 里最小的一张票，但解决的是 Text2SQL 最隐蔽的一类错误：SQL 语法完全正确、执行也成功，但**业务口径是错的**，业务方拿到的数字对不上。

## 0. 范围边界

**这一票只做**：一份 YAML 术语字典 + 加载它的服务 + 一个 `lookup_glossary` 工具。

**这一票不做**：SQL 校验/执行/改写（Ticket 9/10/11）、探针查询能力（探针本质就是用 `execute_sql` 跑一条小查询，不需要单独的工具，Ticket 12 的 SKILL.md 里写清楚用法即可）。

## 1. 为什么需要这个工具（实现前先理解，否则会做成一个没人调用的摆设）

两类真实故障：

**① 口径漂移。** 同一个问题"VIP 客户有多少"，模型三次生成的 SQL 可能是三种完全不同的口径——按累计消费金额 `SUM(amount) > 1000`、按租赁次数 `COUNT(*) > 5`、按注册时长过滤。三次都语法正确、都能跑出数字，但三个数字互相对不上。业务方看到的结论就是"这个 AI 不靠谱"。

**② 时间锚点缺失。** sakila 是冻结在 2005 年的历史样本库（数据集中在 2005-05 到 2005-09）。用户问"最近 3 个月的租赁量"，模型按直觉写 `WHERE rental_date >= DATE_SUB(NOW(), INTERVAL 3 MONTH)`——**结果永远是 0 行**，因为数据集里根本没有"最近 3 个月"。这不是 sakila 特有的问题：任何历史快照型数据集（离线数仓、审计日志归档、冻结的测试库）都会踩。

两类问题的共同解法都是"把口径显式登记下来，让模型查而不是猜"。

## 2. 验收标准

- [ ] `src/main/resources/analytics/glossary.yml` 存在，至少登记 6 条术语 + 1 条时间锚点元规则
- [ ] `GlossaryCatalog` 启动时（`@PostConstruct`）加载一次，后续全内存只读；YAML 格式错误时**启动失败并给出明确报错**（不要静默降级成空字典——那会让模型以为"这个系统没有术语约定"）
- [ ] `lookup_glossary` 工具的 description 里**动态列出**当前所有术语名（不是硬编码的静态文案），加一条新术语后重启，工具描述里自动出现它
- [ ] 精确匹配术语名命中
- [ ] 精确匹配同义词命中（大小写不敏感）
- [ ] 都不命中时返回"没找到 + 当前全部术语列表"，**不做模糊/相似度匹配**
- [ ] 时间锚点元规则可以通过一个固定 term（比如 `__time_anchor__` 或 `时间口径`）查到
- [ ] 单测覆盖：命中术语名、命中同义词、大小写变体、未命中、空入参五种情况
- [ ] 工具不接触数据库（纯内存查询），单测不需要 Testcontainers

## 3. 术语字典格式（`src/main/resources/analytics/glossary.yml`）

```yaml
# 业务术语字典：给 DataAgent 提供权威的口径定义，避免它自己猜。
# 维护方式：AI 生成初稿 → 人工补充业务语义 → 跟 git 走，改 schema 时顺便 review 口径。
# 放 YAML 不放数据库的理由：版本可追溯、改动能走 code review，多一个数据源就多一份维护成本。

meta:
  # 时间锚点元规则——这条不是"某个术语的定义"，是所有相对时间表达的全局前提。
  # sakila 数据冻结在 2005 年，按 NOW() 算"最近 N 个月"永远查不出数据。
  time_anchor:
    description: |
      本数据集是历史快照，不是实时库。所有相对时间表达（"最近N个月"/"近30天"/"上季度"）
      必须以数据集自身的最新时间为基准，不能用 NOW()/CURDATE()。
      数据当前时间 = (SELECT MAX(rental_date) FROM rental)
    sql_fragment: "rental_date >= DATE_SUB((SELECT MAX(rental_date) FROM rental), INTERVAL 30 DAY)"
    example: |
      问"近30天租赁量"应生成：
      SELECT COUNT(*) FROM rental
      WHERE rental_date >= DATE_SUB((SELECT MAX(rental_date) FROM rental), INTERVAL 30 DAY)

terms:
  - term: 活跃客户
    synonyms: [活跃用户, active customer]
    description: |
      近 30 天内至少有 1 笔租赁记录的去重客户。
      注意：这里的"活跃"是行为频次口径，和"高价值客户"（消费金额口径）是两个完全不同的概念，
      不要混用，也不要因为字面相似就替换。
      时间基准遵循 meta.time_anchor（基于数据最大时间，不是 NOW()）。
    sql_fragment: |
      SELECT COUNT(DISTINCT r.customer_id) FROM rental r
      WHERE r.rental_date >= DATE_SUB((SELECT MAX(rental_date) FROM rental), INTERVAL 30 DAY)

  - term: 高价值客户
    synonyms: [高消费客户, VIP客户, VIP]
    description: |
      累计付款金额 >= 100（美元）的客户。金额口径见术语"付款金额"。
      和"活跃客户"是正交的两个维度，一个客户可以是高价值但不活跃。
    sql_fragment: |
      SELECT p.customer_id, SUM(p.amount) AS total
      FROM payment p GROUP BY p.customer_id HAVING SUM(p.amount) >= 100

  - term: 付款金额
    synonyms: [金额, amount, 销售额]
    description: |
      payment.amount，单位美元，含税，可直接 SUM/AVG。
      一笔租赁可能对应多笔付款，算"订单金额"时不要直接 JOIN rental 后 SUM（会重复计数），
      需要先按 rental_id 聚合。

  - term: 业绩归属
    synonyms: [归属, 业务员业绩]
    description: |
      rental.user_id / payment.user_id 是经手业务员（对应主库 sys_user.id），
      dept_id 是归属部门（对应 sys_dept.id）。
      注意：这两列会被系统的数据权限逻辑自动注入过滤条件，你不需要（也不应该）
      自己在 SQL 里写 dept_id/user_id 的过滤条件——写了也会被服务端覆盖。

  # 再补 2 条以上，覆盖真实会被问到的口径
```

### 3.1 为什么每条都要带 `sql_fragment`

模型拿到一句自然语言定义仍然可能翻译错（比如"去重客户"漏掉 `DISTINCT`）。给一段可直接复用/改写的 SQL 片段，把"理解口径"和"翻译成 SQL"这两步里更容易出错的那一步直接省掉。片段不需要是完整可执行的查询，能表达核心口径即可。

## 4. 新增文件清单

| 文件 | 职责 |
|---|---|
| `capability/analytics/glossary/GlossaryEntry.java` | record `GlossaryEntry(String term, List<String> synonyms, String description, String sqlFragment, String example)` |
| `capability/analytics/glossary/GlossaryCatalog.java` | 启动时加载 YAML，建两个索引，提供 `lookup(String)` 和 `allTerms()` |
| `capability/analytics/tools/LookupGlossaryTool.java` | 产出 `ToolCallback`，名字 `lookup_glossary` |

## 5. `GlossaryCatalog` 实现细节

### 5.1 加载

```java
@PostConstruct
void load() {
    // 用 Jackson 的 YAML 支持或 SnakeYAML——先看项目里已有哪个（Spring Boot 自带 SnakeYAML），
    // 不要为这一个文件新引一个 YAML 库
    // 解析失败直接抛异常让应用启动失败：一份格式错误的术语字典比没有字典更危险，
    // 因为运维会以为它在生效
}
```

加载完建两个 `Map<String, GlossaryEntry>`：
- `termIndex`：key 是 `term.toLowerCase()`
- `synonymIndex`：key 是每个 `synonym.toLowerCase()`

两个索引都在 `@PostConstruct` 里一次性建好，之后全生命周期只读——**不需要加锁，不需要缓存层，不需要考虑并发**。这是选择"静态 YAML 而不是数据库表"带来的直接简化。

### 5.2 查询算法（严格按这个顺序，不要加第四步）

```java
public Optional<GlossaryEntry> lookup(String query) {
    if (query == null || query.isBlank()) return Optional.empty();
    String key = query.trim().toLowerCase();
    GlossaryEntry byTerm = termIndex.get(key);
    if (byTerm != null) return Optional.of(byTerm);
    return Optional.ofNullable(synonymIndex.get(key));
}
```

**明确不做向量检索、不做编辑距离、不做子串包含匹配。** 理由（这是这一票最重要的设计决策，写进类 Javadoc）：

> 向量相似度会把"活跃客户"和"高价值客户"匹配到一起——它们字面相似度很高，业务口径却完全正交（一个是行为频次，一个是消费金额）。**一个似是而非的错误匹配比"没匹配到"危险得多**：没匹配到时模型会诚实地说"我不确定这个口径"或去查探针；错误匹配则会让它基于一个看起来很合理的错误定义一路推理下去，产出一个没人能一眼看出错的结论。原则是"宁可不命中，也不能命中错"。

## 6. `lookup_glossary` 工具

### 6.1 description 必须动态生成

```java
public ToolCallback toolCallback() {
    // description 在构造时拼接，把当前所有术语名列进去——模型在 Tool Selection 阶段
    // 就能看到"有哪些口径可以查"，而不是先猜有没有这个工具、再猜要不要调它。
    // 这也是为什么这里用 JsonToolCallback（description 是构造参数）而不是 @Tool 注解
    // （注解的 description 必须是编译期常量，塞不进运行时加载的术语列表）。
    String description = """
            查询业务术语的权威口径定义，返回精确定义和可复用的 SQL 片段。

            当前已登记的术语：%s

            什么时候必须调用它：
            - 用户问题里出现上面列出的任何术语（或它们的同义词）
            - 用户问题涉及"最近N天/月""上季度"这类相对时间——查 %s 拿到时间基准规则，
              本数据集是历史快照，直接用 NOW() 算相对时间会查出空结果

            查不到时会返回全部术语列表，不会给你一个"看起来差不多"的近似结果——
            这时应该老实告诉用户这个口径没有登记，或者用探针查询（execute_sql 跑一条
            小的 SELECT DISTINCT / COUNT）去确认真实数据形态，不要自己编一个口径。
            """.formatted(String.join("、", catalog.allTerms()), TIME_ANCHOR_TERM);
    return new JsonToolCallback("lookup_glossary", description, INPUT_SCHEMA,
            args -> lookup(args.text("term")));
}
```

### 6.2 入参 schema

```json
{"type":"object","properties":{
  "term":{"type":"string","description":"【必填】要查询的业务术语或同义词，例如「活跃客户」"}},
 "required":["term"]}
```

### 6.3 返回文案

命中时：把 `description` + `sql_fragment` + `example` 拼成一段可读文本。

未命中时（文案要能引导模型下一步动作，不能只说"没找到"）：

```
未登记的术语：<用户传入的词>

当前已登记的术语：活跃客户、高价值客户、付款金额、业绩归属、...

这个词没有权威口径定义。不要自己假设一个口径直接写 SQL——
可以用 execute_sql 跑一条探针查询（比如 SELECT DISTINCT <字段> ... LIMIT 10）
确认真实数据形态，或者在回答里明确说明你采用的口径，让用户确认。
```

## 7. 实现顺序

1. 写 `glossary.yml`（先写内容，这决定了数据模型长什么样）
2. `GlossaryEntry` record + `GlossaryCatalog` 加载逻辑 + 单测（加载成功、格式错误时抛异常）
3. `lookup` 算法 + 单测（五种情况全覆盖）
4. `LookupGlossaryTool` + 单测（断言 description 里真的含有术语名、断言未命中文案含全部术语列表）

全程不需要数据库，单测跑得很快，先做完这一票再去啃 Ticket 9/10/11 的 AST 部分也是合理的顺序安排。

## 8. 明确禁止事项

- **不要**用向量检索、模糊匹配、编辑距离、子串包含来找术语（理由见 5.2）
- **不要**把术语字典放数据库表或 Redis——静态 YAML 是刻意选择，好处是版本可追溯 + 零并发问题
- **不要**在 YAML 格式错误时静默降级成空字典，必须启动失败
- **不要**用 `@Tool` 注解注册这个工具（description 需要运行时拼接）
- **不要**为"探针查询"单独做一个工具——探针就是用 `execute_sql` 跑一条小查询，Ticket 12 的 SKILL.md 里写清楚用法即可
- **不要**在术语字典里写任何真实的敏感数据样例
- 其余共享约束同 Ticket 6/7

## 9. 和现有代码的边界

纯新增：`capability/analytics/glossary` 包 + 一个 `ToolCallback` + 一个 YAML 资源文件。不修改任何现有文件（Ticket 7 已经把 `JsonToolCallback` 提升为 public，这一票直接用）。
