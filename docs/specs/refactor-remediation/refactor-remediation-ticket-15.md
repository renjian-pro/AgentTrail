# Ticket 15（Phase 4）：统一 Run/Task/Checkpoint/Event — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 14](refactor-remediation-ticket-14.md)。Blocks [Ticket 16](refactor-remediation-ticket-16.md)、[Ticket 17](refactor-remediation-ticket-17.md)、[Ticket 18](refactor-remediation-ticket-18.md)、[Ticket 19](refactor-remediation-ticket-19.md)、[Ticket 21](refactor-remediation-ticket-21.md)。

## 0. 范围边界

这一票是给业务层 Phase 5-9（Ticket 16-20）提供地基的基础设施票，**本身不改任何业务代码**。四块：

① `RunRepository`/`RunEventStore`/`CheckpointStore` 三个新 repository/store + 建表 SQL 草案
② `TaskCoordinator`/`TaskQueue`（先给内存实现，接口留 Redis 后路）+ `LeaseManager`（包一层现有 `RedisTaskLock`）
③ `CancellationPort`（取消操作写持久状态并广播，替换现在"只 dispose 本地订阅"的语义）
④ Outbox 表 + `OutboxPublisher`（状态变更和事件发布的最终一致性）

**明确不要求**：不要求 DeepResearch/PPT 立刻切换到这套新基础设施（那是 Ticket 17/18 的事，本票只要求基础设施本身有完整测试、能独立验证正确性）；不要求这一票就把 `TaskQueue` 接 Redis（先给内存实现，接口设计要能后续无痛换掉）；不改 `AgentLoopExecutor`/`AgentLoopExecutorFactory`（Ticket 14 已经完成，本票是在它拆好的 6 个模块之上、独立于 Runtime 内部实现新增一层基础设施，`RunLifecycleManager` 后续要不要接入 `CancellationPort` 是本票交付之后的事，不在本票范围内验证）。

---

## 1. `RunRepository` / `RunEventStore` / `CheckpointStore`

三张表对应 `refactor-blueprint.md` §2.8 的 Task 统一模型（字段：`taskId/capabilityId/tenantId/userId/conversationId/status/currentStage/attempt/leaseOwner/leaseUntil/idempotencyKey/checkpointVersion/inputRef/outputRef/errorCode/createdAt/updatedAt`）和 §1.8 的 `EventEnvelope` 事件协议（字段：`eventId/runId/taskId/conversationId/sequence/occurredAt/type/source/visibility/payload`）。参考现有 [db/schema.sql](../../src/main/resources/db/schema.sql) 的风格（`CREATE TABLE IF NOT EXISTS`、InnoDB + utf8mb4、每列一条 COMMENT、索引旁写清楚支撑哪个查询模式），建表 SQL 草案如下：

```sql
-- Run/Task 统一模型（Phase 4，refactor-blueprint.md §2.8）：一次长任务（DeepResearch/PPT/
-- 未来的其它能力）的状态、归属和恢复位点。这张表替代 DeepResearchTaskRegistry 的纯内存
-- ConcurrentHashMap（重启丢失、从不清理终态任务）和 PptTaskStore 各自为政的 checkpoint 落库，
-- 两条业务线以后共用同一张表、同一套 Repository。不引入通用 Workflow 抽象（见 Ticket 17 §0），
-- capability_id/current_stage 只是普通字符串标签，不绑定任何接口类型。

CREATE TABLE IF NOT EXISTS agent_run
(
    task_id             VARCHAR(64)  NOT NULL COMMENT '任务标识，业务侧生成（建议 UUID），主键',
    capability_id       VARCHAR(100) NOT NULL COMMENT '能力包标识，如 deepresearch/ppt-generation（纯字符串标签，不对应任何通用接口）',
    tenant_id           VARCHAR(100) NULL COMMENT '租户标识；当前多租户未动工，允许为 NULL，Phase 4 只占位不强制',
    user_id             VARCHAR(100) NOT NULL COMMENT '任务归属用户，取消/查询的权限判定主体',
    conversation_id     VARCHAR(100) NULL COMMENT '关联会话标识，可为空（非对话触发的任务）',
    status              VARCHAR(20)  NOT NULL COMMENT 'PENDING/RUNNING/PAUSED/CANCELLING/CANCELLED/COMPLETED/FAILED',
    current_stage        VARCHAR(100) NULL COMMENT '当前所在阶段名（如 DeepResearchStage/PptState 的某个枚举值字符串），用于恢复时定位——各能力包自己的枚举，不是通用节点类型',
    attempt             INT          NOT NULL DEFAULT 0 COMMENT '当前节点的重试次数',
    lease_owner         VARCHAR(100) NULL COMMENT '当前持有该任务执行权的 worker 实例标识，权威状态在 Redis（见 LeaseManager），这里是查询用的最近已知值',
    lease_until         BIGINT       NULL COMMENT '租约到期时间（epoch millis），同上，非权威，仅供查询',
    idempotency_key     VARCHAR(200) NULL COMMENT '幂等键，创建任务时可选传入，防止客户端重试导致重复提交',
    checkpoint_version  BIGINT       NOT NULL DEFAULT 0 COMMENT '当前恢复点版本号，对应 agent_run_checkpoint 里最新的一条',
    input_ref           VARCHAR(500) NULL COMMENT '输入引用（原始请求存储位置，避免大字段直接进这张表）',
    output_ref          VARCHAR(500) NULL COMMENT '产物引用（ArtifactId 或存储路径），不直接存产物内容',
    error_code          VARCHAR(100) NULL COMMENT '失败时的错误分类码，成功/进行中为 NULL',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (task_id),
    UNIQUE KEY uk_idempotency_key (idempotency_key),
    -- 查询场景固定是「这个用户当前在跑的任务」（供 3a-5 式的刷新恢复用）和「按会话查任务」
    KEY idx_user_status (user_id, status),
    KEY idx_conversation (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'Run/Task 统一模型：一次长任务的状态与恢复位点';

-- 事件存储（RunEventStore），承载 EventEnvelope 协议（refactor-blueprint.md §1.8）。
-- sequence 按 run_id 严格单调递增，断线重连用 afterSequence 做增量拉取和去重。

CREATE TABLE IF NOT EXISTS agent_run_event
(
    id           BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键，写入序，不对外暴露',
    event_id     VARCHAR(64)   NOT NULL COMMENT '事件全局唯一标识，客户端用于去重（Last-Event-ID）',
    run_id       VARCHAR(64)   NOT NULL COMMENT '所属 Run 标识',
    task_id      VARCHAR(64)   NULL COMMENT '所属 Task 标识，可为空（Run 不一定对应长任务）',
    conversation_id VARCHAR(100) NULL COMMENT '关联会话标识，供按会话过滤事件用',
    sequence     BIGINT        NOT NULL COMMENT '同一 run_id 内严格单调递增的序号，从 1 开始',
    occurred_at  BIGINT        NOT NULL COMMENT '事件发生时刻（epoch millis）',
    type         VARCHAR(50)   NOT NULL COMMENT 'RunStarted/ModelDelta/ToolStarted/ToolCompleted/CheckpointSaved/Paused/RunCompleted/RunFailed/RunCancelled',
    source       VARCHAR(50)   NOT NULL COMMENT '事件来源模块，如 round-driver/tool-executor/lifecycle-manager',
    visibility   VARCHAR(20)   NOT NULL COMMENT 'CLIENT（需要下发给前端）/ INTERNAL（仅服务端消费，如指标埋点）',
    payload      LONGTEXT      NOT NULL COMMENT '事件载荷，JSON 格式，结构随 type 变化',
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_event_id (event_id),
    -- 「按 run_id 正序重放」和「按 run_id + afterSequence 增量拉取」是仅有的两种读法，
    -- 复合唯一索引同时保证 sequence 不重复、又直接支撑这两种查询
    UNIQUE KEY uk_run_sequence (run_id, sequence)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'RunEventStore：EventEnvelope 事件流水，支持断线重放';

-- Checkpoint 存储（CheckpointStore）：各能力包自己状态机的可恢复快照，和 agent_run.checkpoint_version
-- 配合使用——agent_run 存"当前指向哪个版本"，这张表存每个版本的实际状态内容，1:N 关系，
-- 旧版本按保留策略清理（不在本票范围，先不删，观察实际增长速度再定策略）。不是某个通用
-- Workflow 引擎的快照格式，state_snapshot 的具体结构完全由各能力包自己的 State record 决定
-- （DeepResearchState/PptGenerationContext），CheckpointStore 只管存取，不解析内容。

CREATE TABLE IF NOT EXISTS agent_run_checkpoint
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    task_id            VARCHAR(64)  NOT NULL COMMENT '所属 Task 标识',
    checkpoint_version BIGINT       NOT NULL COMMENT '版本号，同一 task_id 内单调递增',
    stage              VARCHAR(100) NOT NULL COMMENT '保存快照时所在的阶段（各能力包自己的枚举值字符串，如 DeepResearchStage/PptState）',
    state_snapshot     LONGTEXT     NOT NULL COMMENT '状态快照内容，JSON 格式，具体结构由各能力包自己的 State 类型定义',
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '保存时间',
    PRIMARY KEY (id),
    -- 恢复时读「这个任务最新的一条」，也可能需要按版本号精确定位（调试/回滚），
    -- 唯一索引顺带防止同一任务同一版本号重复写入
    UNIQUE KEY uk_task_version (task_id, checkpoint_version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'CheckpointStore：Runtime/Workflow 的可恢复快照';
```

`RunRepository`/`RunEventStore`/`CheckpointStore` 三个接口分别对应上面三张表，落地成 JDBC 实现时参照现有 `JdbcTraceStore` 的写法（`loop/trace` 包已有先例：接口定义在核心层，JDBC 实现作为一个可替换的适配器）。

**这一步把 Ticket 13 可能还没细化的事件协议在这里落地成真实存储实现**——如果 Ticket 13 交付时 `EventEnvelope` 只是一个内存态的 Java record、没有持久化，本票是第一次让它真正落库；如果 Ticket 13 已经顺手接了一个简化版存储，本票需要核实字段是否和上面的表结构一致，不一致的话以本票的设计为准做一次迁移，不要留两套不一致的事件存储实现同时存在。

---

## 2. `TaskCoordinator`/`TaskQueue` + `LeaseManager`

### 2.1 先验证：`RedisTaskLock` 现有实现要不要重写

**开工前先读** [loop/task/RedisTaskLock.java](../../src/main/java/com/agenttrail/loop/task/RedisTaskLock.java)。本次审计复核了它的实现，结论是：**没有发现明确缺陷，`LeaseManager` 应该包一层，不重写**——

- `tryAcquire` 用 Redis `SETNX`（`RBucket.setIfAbsent`），天然原子；
- `renew`/`release` 都是"先判归属再动作"的单条 Lua 脚本（`RENEW_SCRIPT`/`RELEASE_SCRIPT`），避免了"GET 判断 + EXPIRE"拆成两条命令中间出现的锁被抢占窗口；
- 锁带 TTL 自愈，持有者崩溃不需要额外心跳/watchdog；
- `releaseAll()` 挂 `@PreDestroy`，优雅关闭时主动释放，不用干等 TTL；
- `startAutoRenewal()` 故意不在构造函数里自动开启，是为了让测试能用"一个不再续期的实例"模拟持有者崩溃——这是经过深思熟虑的设计取舍，不是遗漏。

`LeaseManager` 的语义和 `RedisTaskLock` 完全对应（"某个资源当前归哪个实例执行权"），只是把参数名从 `conversationId` 泛化成更通用的 `resourceId`（本票里主要用来传 `taskId`），内部直接持有一个 `RedisTaskLock` 实例并委托，不复制 Lua 脚本逻辑：

```java
public interface LeaseManager {
    /** @return true 表示本实例拿到了租约 */
    boolean tryAcquire(String resourceId, Duration ttl);

    /** @return true 表示确实是本实例持有该租约且续期成功 */
    boolean renew(String resourceId, Duration ttl);

    /** @return true 表示确实由本实例释放了持有的租约 */
    boolean release(String resourceId);
}

public class RedisLeaseManager implements LeaseManager {
    private final RedisTaskLock delegate; // 直接复用，不重写内部逻辑

    public RedisLeaseManager(RedisTaskLock delegate) {
        this.delegate = delegate;
    }

    @Override public boolean tryAcquire(String resourceId, Duration ttl) { return delegate.tryAcquire(resourceId); }
    @Override public boolean renew(String resourceId, Duration ttl) { return delegate.renew(resourceId); }
    @Override public boolean release(String resourceId) { return delegate.release(resourceId); }
}
```

注意一个现状限制：`RedisTaskLock` 把 `instanceId` 绑死在构造函数里（一个 `RedisTaskLock` 实例对应一个应用实例），这对"任务锁"场景本来就是对的语义（校验的是"这个资源归哪个实例"），`LeaseManager` 复用同样的设计，不需要改——如果未来需要"同一个应用实例内、不同调用方持有不同粒度租约"这种更细的语义，那是真正的需求变化，届时再评估要不要扩展 `instanceId` 维度，本票不做超前设计。

### 2.2 `TaskQueue`：内存实现，接口留 Redis 后路

```java
public interface TaskQueue {
    void enqueue(TaskId taskId, TaskPayload payload);

    /** 长轮询式获取一个可执行任务；visibilityTimeout 内未 ack/nack 视为该 worker 失联，任务重新可见 */
    Optional<QueuedTask> poll(String workerId, Duration visibilityTimeout);

    void ack(TaskId taskId);

    void nack(TaskId taskId, Duration retryDelay);
}
```

内存实现（`InMemoryTaskQueue`）用 `DelayQueue`/`PriorityBlockingQueue` 组合即可满足语义（`nack` 后按 `retryDelay` 重新可见、`poll` 的 `visibilityTimeout` 用一个后台清理线程扫描"取出未 ack 超时的任务重新入队"）。接口方法签名不能带任何内存实现特有的细节（比如不能返回 `CompletableFuture` 之外的内存专属类型），确保后续换成 Redis Streams/List 实现时业务调用方零改动——这是本票唯一要求"接口设计留后路"的地方，具体 Redis 实现本身不是本票交付物。

### 2.3 `TaskCoordinator`：编排层

`TaskCoordinator` 组合 `TaskQueue` + `LeaseManager` + `RunRepository`，对外暴露业务需要的动作（提交、查询、取消、恢复），是本票四个基础设施里唯一直接依赖前三者的编排类，业务层（Ticket 17/18）后续只依赖 `TaskCoordinator`，不直接触碰 `TaskQueue`/`LeaseManager`：

```java
public interface TaskCoordinator {
    TaskId submit(CapabilityId capabilityId, TaskPayload payload, String idempotencyKey);
    Optional<RunStatus> query(TaskId taskId);
    void cancel(TaskId taskId, CancellationReason reason);
    TaskId resume(TaskId taskId);
}
```

**命名说明（2026-08-11）**：这里用 `CapabilityId`（标识"这是哪个能力包提交的任务"，比如
`deepresearch`/`ppt`）而不是 `WorkflowId`——本项目已经决定不引入通用 `WorkflowDefinition<S>` 抽象
（见 Ticket 17 §0），`TaskCoordinator` 不认识任何"Workflow"类型的实例，只是按标签路由/追踪任务，
用 `WorkflowId` 命名会暗示这里绑定了一个不存在的抽象，故改名。

---

## 3. `CancellationPort`

### 3.1 问题：现在的"取消"只是本地订阅 dispose

`refactor-blueprint.md` §2.3 提到"客户端断连不等于任务取消"——本次审计复核了现有的跨实例中断机制（[loop/task/InterruptBroadcaster.java](../../src/main/java/com/agenttrail/loop/task/InterruptBroadcaster.java) + [RedisInterruptBroadcaster.java](../../src/main/java/com/agenttrail/loop/task/RedisInterruptBroadcaster.java)），确认现状：`RedisInterruptBroadcaster` 是纯 Redis Pub/Sub 广播，`broadcastStop(conversationId)` 只是把消息发给**当前正在监听的实例**，不落任何持久状态——如果某个实例在广播发生时没在监听（比如刚重启、还没订阅上），或者任务此刻还没被任何实例领取，这条取消请求会直接丢失，没有人会补发。这正是"客户端断连不等于任务取消"问题的根因：取消是一个瞬时事件，不是一个可查询的状态。

### 3.2 设计：持久状态 + 广播两层

`CancellationPort` 不是重新发明广播机制，是在现有 `InterruptBroadcaster` 之上加一层持久化状态：

```java
public interface CancellationPort {
    /** 请求取消：先写 agent_run.status = CANCELLING（持久化，任何时刻查询都能看到"这个任务被要求停止"），
     *  再尽力广播（复用 InterruptBroadcaster，用于正在跑的实例能立刻响应，不必等下一次轮询）。 */
    void requestCancellation(TaskId taskId, CancellationReason reason);

    /** 权威判断依据：查 agent_run.status，不依赖是否收到过广播——
     *  即使广播丢失，执行方在下一次检查点也能查到"我被要求停止了"。 */
    boolean isCancellationRequested(TaskId taskId);
}
```

`isCancellationRequested` 是这个设计的关键：广播是"尽快通知"的优化路径，不是唯一路径；持久状态是权威真相，任何执行节点（哪怕是刚从 checkpoint 恢复、从没收到过广播的新实例）在每个安全点检查一次这个状态，就能保证取消请求最终一定生效，不依赖"恰好在广播那一刻有人在听"。

`RunLifecycleManager`（Ticket 14 拆出的模块）后续接入 `CancellationPort` 是 Ticket 17/18 迁移 DeepResearch/PPT 时才做的事，本票只交付 `CancellationPort` 本身和它的测试，不改 `RunLifecycleManager`。

---

## 4. Outbox 表 + `OutboxPublisher`

"状态变更"（写 `agent_run`/`agent_run_checkpoint`）和"事件发布"（写 `agent_run_event` 供 SSE/前端消费）要满足最终一致——如果两者不在同一个数据库事务里保证原子性，或者事件发布方式本身不可靠（比如直接内存推送、进程崩溃就丢失），会出现"状态已经变了但事件没发出去"或者反过来的不一致。给出标准 Outbox 模式实现：一张 outbox 表，和状态变更**同一个数据库事务**写入；一个轮询发布器，定期扫描未发布记录，发布后标记状态。

```sql
-- Outbox 模式：状态变更和事件发布的最终一致性。写 agent_run/agent_run_checkpoint 的同一个事务里
-- 顺带写一行到这张表，事务提交后由 OutboxPublisher 轮询发布，发布器崩溃重启后未标记为 PUBLISHED
-- 的行会被重新拾起补发——"写状态"和"发布事件"要么都成功，要么都不成功，不会出现只做了一半。

CREATE TABLE IF NOT EXISTS agent_run_outbox
(
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，兼作发布顺序参考',
    aggregate_type VARCHAR(50)  NOT NULL COMMENT '聚合类型，如 agent_run/agent_run_checkpoint',
    aggregate_id   VARCHAR(64)  NOT NULL COMMENT '聚合标识，通常是 task_id 或 run_id',
    event_type     VARCHAR(50)  NOT NULL COMMENT '要发布的事件类型，对应 agent_run_event.type',
    payload        LONGTEXT     NOT NULL COMMENT '待发布的事件载荷，JSON 格式',
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PUBLISHED/FAILED',
    retry_count    INT          NOT NULL DEFAULT 0 COMMENT '发布失败重试次数',
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间（与业务状态变更同一事务）',
    published_at   TIMESTAMP    NULL COMMENT '实际发布成功时间，未发布为 NULL',
    PRIMARY KEY (id),
    -- 轮询发布器固定查「还没发布的，按写入顺序」，这个索引直接支撑该查询
    KEY idx_status_created (status, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'Outbox：状态变更与事件发布的最终一致性中转表';
```

`OutboxPublisher` 是一个定时轮询任务（沿用项目已有的定时任务机制，比如 Skill 对账用的那一套调度方式，不新发明一套调度框架）：查 `status = PENDING` 的行，按 `created_at` 正序逐条发布（写入 `agent_run_event` + 触发 SSE 推送 hook），成功后标记 `PUBLISHED`，失败则 `retry_count + 1` 并保持 `PENDING`（或超过重试上限后标记 `FAILED` 并告警，具体上限值留到实现时按现有告警机制的模式定，不在本票预先写死）。**如果团队已经有类似的最终一致性机制**（比如现有 `TraceStore` 或其它模块已经实现过 outbox 风格的东西），本票应该复用而不是重新建一套——开工前先搜一遍代码库确认没有现成实现可以直接扩展。

---

## 5. Testing Decisions

- `LeaseManager` 要有**多实例竞争测试**：模拟两个进程（两个 `RedisLeaseManager` 实例，指向同一个 Redis）抢同一个 `resourceId` 的租约，断言只有一个成功；持有者的续期失败后另一个实例能成功抢到；参照现有 `AgentTaskManagerCrossInstanceIT`/`RedisTaskLockIT` 的测试模式，起真实 Redis（Testcontainers）。
- `CheckpointStore` 要有"保存-恢复"往返测试：保存一个状态快照，恢复读出，断言内容一致；同一 `task_id` 保存多个版本后，"读最新版本"和"读指定版本号"两条路径都要覆盖。
- Outbox 要有"写入状态变更但发布器崩溃重启后仍能补发事件"的测试：模拟事务提交（outbox 行落库）后发布器还没跑就"崩溃"（测试里直接不启动发布器/中途停止），重启后启动发布器，断言之前未发布的行最终被发布，且不重复发布已经 `PUBLISHED` 的行。
- `CancellationPort` 测试：`requestCancellation` 后即使目标实例当时没在监听广播（模拟：先取消订阅再触发取消），`isCancellationRequested` 依然返回 `true`——这条测试直接对应 3.1 分析的问题场景，是这个类存在的核心价值，必须覆盖。
- `TaskQueue` 内存实现测试：`nack` 后按 `retryDelay` 重新可见；`poll` 后未 `ack`/`nack`，超过 `visibilityTimeout` 后任务重新可见（模拟 worker 失联）。
- 集成测试延续项目一贯约定，起真实 MySQL/Redis（Testcontainers），不用 H2 替代——这一点尤其重要，因为本票的核心价值就在并发/持久化语义的正确性，内存 mock 验证不了这些。

## 6. 验收标准

照抄 `refactor-blueprint.md` §6 Phase 4："**任何长任务都可通过 taskId 查询、取消、恢复和重放事件；单实例/多实例测试语义一致**"——即：`TaskCoordinator.query(taskId)` 能查到状态、`cancel(taskId)` 能可靠取消（即使当前没有实例在监听）、`resume(taskId)` 能从 `CheckpointStore` 最新版本恢复、`RunEventStore` 支持从任意 `sequence` 之后重放；上述能力在单实例部署和多实例部署（Testcontainers 模拟两个进程）下行为一致。

## Out of Scope

- DeepResearch/PPT 切换到这套新基础设施——这是 Ticket 17/18 的范围，本票只交付基础设施本身。
- `TaskQueue` 的 Redis 实现——本票只要求接口设计能后续无痛替换，Redis 版实现留给需要真实分布式队列语义时再排期（可能在 Ticket 17/18 落地时按需触发，也可能更晚）。
- `RunLifecycleManager`（Ticket 14 拆出的模块）接入 `CancellationPort` 的改造——本票只交付 `CancellationPort` 本身，接入是使用方的事。
- Outbox 发布失败的重试上限、告警阈值等运营参数的精确调优——先给出机制，具体数值留到接入真实流量后再校准。
- 多租户强约束（`tenant_id` 目前允许为 NULL）——`refactor-remediation.md` 的 Out of Scope 已经明确多租户改造是独立立项，本票的表结构只做到"字段占位、不阻塞未来接入"。
