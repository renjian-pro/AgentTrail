# Ticket 4／9：审计哈希链（含 TraceStore 首次生产接线） — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。不依赖其它票，可以
> 并行开工。

## 0. 范围边界

**这一票只做**：
1. **把 `TraceStore`（issue #17，已实现但从未接入生产装配）第一次接入** `AgentLoopExecutorFactory`
   ——现状核实：`AgentLoopExecutorConfig.java` 没有任何 `TraceStore`/`JdbcTraceStore` 的 `@Bean`，
   `buildExecutor`/`forAnalytics` 两处 `Builder` 链都没有调用 `.traceStore(...)`，生产环境现在
   一条审计记录都不会落库。这是和 Ticket 2 的 `PauseConfig` 同一类"实现完成但没插电"的缺口。
2. `agent_trace` 表加哈希链两列，`JdbcTraceStore.save()` 落库时顺便算好
3. 提供一个校验方法/接口，重算哈希链和落库值比对

## 1. `TraceStore` 生产接线

```java
// AgentLoopExecutorConfig 新增
@Bean
public TraceStore traceStore(@Qualifier("dataSource") DataSource dataSource) {
    return new JdbcTraceStore(dataSource);
}
```

`agentLoopExecutorFactory(...)` 新增 `TraceStore traceStore` 参数，`AgentLoopExecutorFactory`
新增字段（和 Ticket 2 的 `PauseConfig` 同样的顾虑：**先检查**有没有测试直接 `new
AgentLoopExecutorFactory(...)`，如果有，只能加新的 telescoping 重载，不能改已有构造函数签名）。
`buildExecutor`/`forAnalytics` 两处 `Builder` 链都加 `.traceStore(traceStore)`。

**这一步落地后，`agent_trace` 表会第一次真正开始积累生产数据**——这对 Ticket 7（评测体系要从
真实 trace 筛样本）和 Ticket 2 的"跨会话/自然日 Budget"都是前置依赖，Further Notes 里要更新
一下总纲文档的依赖说明（总纲原来假设 TraceAudit 数据已经在生产环境流动，这个假设在这一票落地
之前是不成立的）。

## 2. `agent_trace` 表加哈希链两列

```sql
-- 追加到 schema.sql 里 agent_trace 表定义之后（ALTER，不改建表语句本身，避免影响老库）
ALTER TABLE agent_trace ADD COLUMN prev_hash VARCHAR(64) NULL COMMENT '上一条记录的 hash，本会话第一条为 NULL';
ALTER TABLE agent_trace ADD COLUMN hash VARCHAR(64) NULL COMMENT 'SHA-256(关键字段拼接 + prev_hash)，写入后不可再改';
```

两列允许 NULL 是为了兼容这一票之前就存在的历史记录（它们没有哈希链，不回填、不强行伪造一份
"假历史"）——校验逻辑只从"这一票上线之后、`hash` 非空的第一条记录"开始，见第 4 节。

## 3. 哈希计算：按 `conversationId` 分链，写入时机在 `JdbcTraceStore.save()` 内部

**不改 `TraceRecord` 的形状**——它是 `AgentLoopExecutor.recordTrace` 构造的公开记录类型，哈希是
存储层的内部关注点，不需要让调用方（`AgentLoopExecutor`）知道或参与计算。整个逻辑封装在
`JdbcTraceStore.save()` 内部：

```java
private static final String LAST_HASH_SQL = """
        SELECT hash FROM agent_trace WHERE conversation_id = ? ORDER BY id DESC LIMIT 1 FOR UPDATE
        """;

@Override
@Transactional  // 新增注解，SELECT ... FOR UPDATE 必须在事务内才有意义
public void save(TraceRecord record) {
    String prevHash = jdbcClient.sql(LAST_HASH_SQL)
            .param(record.conversationId())
            .query(String.class)
            .optional()
            .orElse(null);
    String hash = computeHash(record, prevHash);
    jdbcClient.sql(INSERT_SQL_WITH_HASH)   // 原 INSERT_SQL 加两列
            .param(record.conversationId())
            /* ... 原有参数不变 ... */
            .param(prevHash)
            .param(hash)
            .update();
}

private static String computeHash(TraceRecord record, String prevHash) {
    String payload = String.join("|",
            record.conversationId(), String.valueOf(record.round()),
            nullToEmpty(record.inputData()), nullToEmpty(record.outputData()),
            String.valueOf(record.promptTokens()), String.valueOf(record.completionTokens()),
            String.valueOf(record.success()), nullToEmpty(record.errorMessage()),
            String.valueOf(record.recordedAtMillis()), nullToEmpty(prevHash));
    return sha256Hex(payload);
}
```

**关键设计取舍——并发写入用 `SELECT ... FOR UPDATE` 串行化**：同一个 `conversationId` 理论上
大部分时候是单飞执行（`AgentTaskManager` 保证同一会话同时只有一个循环在跑），但**先验证**
一点：DeepResearch 的 Plan-Execute 并发子任务（`Semaphore(3)`）如果内部也挂了
`TraceStore`（走 `forInternalOrchestration`/内部编排执行器），是不是可能出现同一个
`conversationId` 下多条记录并发写入——如果是，`SELECT ... FOR UPDATE` + 事务是必须的，不能
省略；如果内部编排各自用独立的 `conversationId`（不共享同一个会话号），这个并发问题其实不存在，
但这一票**按"可能并发"的更保守假设实现**，多一次行锁的开销在这个场景下可以接受。

`nullToEmpty`/`sha256Hex` 是两个私有静态 helper，`sha256Hex` 用 `java.security.MessageDigest`，
不引入新依赖。

## 4. 校验工具

```java
// TraceStore 接口新增方法（InMemoryTraceStore 可以简单实现或抛 UnsupportedOperationException——
// 哈希链防篡改这个能力本来就只对持久化实现有意义）
Optional<Integer> verifyChain(String conversationId);  // 返回值：空=完整；非空=第一条不一致记录的 round
```

```java
// JdbcTraceStore 实现：按 id 升序取出这个会话所有 hash 非空的记录，从第一条开始重算，
// 逐条比对 hash 是否等于本地重算值，且 prev_hash 是否等于上一条的 hash
@Override
public Optional<Integer> verifyChain(String conversationId) {
    List<TraceRecordWithHash> records = /* 按 id ASC 查出 conversationId 的全部记录 + hash/prev_hash 列 */;
    String expectedPrevHash = null;
    for (TraceRecordWithHash record : records) {
        if (record.hash() == null) {
            continue;  // 这一票上线之前的老记录，没有哈希链，跳过不校验
        }
        if (!Objects.equals(record.prevHash(), expectedPrevHash)
                || !Objects.equals(record.hash(), computeHash(record.toTraceRecord(), record.prevHash()))) {
            return Optional.of(record.round());
        }
        expectedPrevHash = record.hash();
    }
    return Optional.empty();
}
```

暴露一个内部管理端点（不是公开 API，**先验证**项目里有没有现成的"内部管理接口"约定/路径前缀
可以对齐，没有的话新开 `GET /internal/audit/{conversationId}/verify`，返回 `{"tampered":
false}` 或 `{"tampered": true, "atRound": N}`）。

## 5. Testing Decisions

- 单条写入：验证 `prev_hash`/`hash` 都非空，`hash` 等于本地重算值
- 连续多条写入（同一 `conversationId`）：第二条的 `prev_hash` 等于第一条的 `hash`
- 篡改检测：正常写入 3 条后，直接用 SQL 改掉中间一条的 `output_data`（不经过 `save()`），
  调用 `verifyChain` 应该返回该条的 `round`
- 并发写入测试（重点）：多线程并发对同一个 `conversationId` 调用 `save()`，验证最终链条完整
  （用 `verifyChain` 断言返回空），且记录数等于写入次数（没有丢记录）——这是验证第 3 节
  "`FOR UPDATE` 串行化"是否真的有效的关键测试，不能只测单线程场景
- 生产接线的回归测试：`@SpringBootTest` 加载完整上下文后 `TraceStore` Bean 非空，跑一次端到端
  对话后 `agent_trace` 表确实新增了记录（验证不是"Bean 存在但没接到执行器上"这种半吊子状态）
- 集成测试起真实 MySQL（`SELECT ... FOR UPDATE` 的锁行为在 H2 上不可信）

## Out of Scope

- 老历史记录（这一票上线前写入的）不回填哈希链
- `InMemoryTraceStore` 的哈希链实现（防篡改对内存实现没有意义）
- 校验端点的鉴权细节（先假设走已有的登录拦截器，管理员权限校验如果需要更细粒度，另开票）
