# Ticket 3／9：DeepResearch/PPT 取消端点 + 刷新恢复 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。不依赖 Ticket 1/2，可以
> 并行开工。

## 0. 范围边界

**这一票只做**：DeepResearch/PPT 各加一个取消端点、DeepResearch 补上缺失的越权校验、加一个
"我有哪些还在跑的任务"查询端点供前端刷新后重新接上轮询。

**这一票不做**：前端页面改造（另开前端票）、DeepResearch 的持久化改造（`DeepResearchTaskRegistry`
本来就是"纯内存、重启即丢"的**有意识范围取舍**，这一票不改变这一点，取消功能建在现有的内存
模型上）。

## 1. 开工前必须核实的两个现状（已经查过，直接给结论，不用重新验证）

1. **`DeepResearchController.status(taskId)` 现在完全没有归属校验**（`web/DeepResearchController.java`
   第 80-85 行，直接 `taskRegistry.find(taskId)`，不看当前登录用户是谁）——这是一个真实存在、
   还没修的越权点，`../phase2a-auth/backend-phase2-auth.md` 的越权修复清单当时只覆盖了 PPT，没覆盖 DeepResearch。
   这一票要顺手把它修掉，不是新发现另开一张票——加取消端点本来就要设计"谁能取消谁的任务"，
   这个校验逻辑和补 `status()` 的校验是同一段代码。
2. **PPT 这边没有这个问题**——`PptGenerationController` 的 `resume`/`status`/`download` 都已经在走
   `pptGenerationService.describe(userId, taskId)` 做归属校验，这一票新增的 `cancel` 端点照抄
   这个已有模式即可。
3. **两边都是 `ExecutorService`**（`PptGenerationConfig`/`DeepResearchConfig` 的
   `pptGenerationExecutor`/`deepResearchExecutor` 都是 `@Bean(destroyMethod = "shutdown")
   ExecutorService`，不是裸 `Executor`），但目前代码里都在用 `.execute(Runnable)`，没有保留
   `Future` 句柄——取消端点需要这个句柄，这是要改的地方。

## 2. DeepResearch：补校验 + 取消

### 2.1 `DeepResearchTaskRegistry` 加 userId + `Future` 句柄

```java
// 记录形状需要能同时存 userId 和 Future——DeepResearchTaskResponse 是对外的响应 DTO，
// 不应该把 Future 混进去序列化，这里在 registry 内部单独维护一个 Map<Long, TaskHandle>

private record TaskHandle(String userId, Future<?> future) {}

private final Map<Long, TaskHandle> handles = new ConcurrentHashMap<>();

long start(String userId, Future<?> future) {
    long taskId = taskIdSequence.incrementAndGet();
    tasks.put(taskId, DeepResearchTaskResponse.running(taskId));
    handles.put(taskId, new TaskHandle(userId, future));
    return taskId;
}

boolean belongsTo(long taskId, String userId) {
    TaskHandle handle = handles.get(taskId);
    return handle != null && java.util.Objects.equals(handle.userId(), userId);
}

/** @return true 表示成功发起取消请求；找不到任务或任务已结束返回 false */
boolean cancel(long taskId) {
    TaskHandle handle = handles.get(taskId);
    if (handle == null || handle.future().isDone()) {
        return false;
    }
    handle.future().cancel(true);
    tasks.put(taskId, DeepResearchTaskResponse.cancelled(taskId));
    return true;
}
```

`DeepResearchTaskResponse` 需要新增一个 `cancelled(taskId)` 工厂方法和一个新的状态值
（**先验证** `DeepResearchTaskResponse` 现在的状态字段是字符串还是枚举，照着现有的
`running`/`success`/`failed` 那三个工厂方法的写法加第四个，不要另起一套形状）。

### 2.2 `start()` 调用点改造（Controller 里）

原来是 `taskRegistry.start()` 后再 `deepResearchExecutor.execute(...)`，两步顺序要倒过来——
必须先拿到 `Future` 才能登记：

```java
long[] taskIdHolder = new long[1];
Future<?> future = deepResearchExecutor.submit(() -> {
    long taskId = taskIdHolder[0];
    try {
        // 原有的 try 块内容不变
    } catch (RuntimeException failure) {
        // 原有的 catch 块内容不变
    }
});
taskIdHolder[0] = taskRegistry.start(userId, future);
```

**这段有一个先有鸡还是先有蛋的问题**：`start()` 要拿到 `future` 才能登记，但 `future` 对应的
`Runnable` 内部又要用到 `taskId`（原代码 `taskRegistry.complete(taskId, report)` 这类调用）。
上面用一个单元素数组做可变闭包捕获是一种解法，但**不优雅、容易读错**——更干净的做法是让
`taskRegistry.start()` 先分配号（不需要 `future`），登记时状态是"待运行"，`submit` 拿到 `future`
后再用一个 `taskRegistry.attachFuture(taskId, future)` 方法把两者关联起来（两次 map 写入，
但语义更清楚）。**实现时选后一种写法**，上面的单元素数组只是说明问题，不是要照抄的最终代码。

### 2.3 取消端点

```java
@PostMapping("/agent/v1/deepresearch/{taskId}/cancel")
public DeepResearchTaskResponse cancel(@PathVariable long taskId) {
    String userId = currentUserId();
    if (userId != null && !taskRegistry.belongsTo(taskId, userId)) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DeepResearch 任务不存在: " + taskId);
    }
    taskRegistry.cancel(taskId);
    return taskRegistry.find(taskId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "DeepResearch 任务不存在: " + taskId));
}
```

`status(taskId)` 同步加上一样的 `belongsTo` 校验（这就是第 1 节第 1 条要顺手修的地方）。

### 2.4 已知局限：`Future.cancel(true)` 是尽力而为，不保证立即停止

`DeepResearchService.research(...)` 是一次不带 checkpoint 的单体调用（需求澄清→主题生成→
逐任务检索→综合报告全部在一次方法调用里跑完），中途有没有会响应中断的阻塞点（HTTP 调用、
`Thread.sleep`）**没有验证过**——`Future.cancel(true)` 会给执行线程发 `interrupt()`，但如果内部
循环从头到尾都是 CPU 密集或者调用了不检查中断状态的阻塞 API，线程可能会跑到自然结束才停。
这一票按"标记为已取消 + 尽力中断"实现，不保证协作式取消一定在几秒内生效——这是明确写进
验收标准和 Further Notes 的已知限制，不是要在这一票里解决的问题（要做到真正协作式取消，
需要 `DeepResearchService` 内部在每个子任务边界检查一个取消令牌，那是一次更大的改造）。

## 3. PPT：加取消端点

### 3.1 `PptState` 加 `CANCELLED`

**先验证**：全局搜索 `PptState` 的 `switch`（尤其 `nextState(current)` 的实现和任何遍历
`ORDER` 列表的地方），确认新增一个不在 `ORDER` 序列里的终态常量不会导致这些地方缺分支或者
产生非预期的推进行为——`CANCELLED` 是通过 `run()` 循环里的提前 `return` 到达的，不通过
`nextState()` 正常流转，理论上不需要出现在 `ORDER` 里，但要实际编译验证一遍。

### 3.2 `PptTaskStore` 加 `requestCancel`/`markCancelled`

镜像现有的 `markFailed(taskId, current, errorMsg)`（`InMemoryPptTaskStore`/`JdbcPptTaskStore`
两个实现都要加）：

```java
void requestCancel(long taskId);   // 置一个 cancel_requested 标记，不立即改 status
void markCancelled(long taskId, PptState atState);  // status 改成 CANCELLED
boolean isCancelRequested(long taskId);
```

`JdbcPptTaskStore` 那份需要给 `ppt_generation_task` 表加一列 `cancel_requested BOOLEAN NOT NULL
DEFAULT FALSE`（`ALTER TABLE`，追加到 `schema.sql`，风格对齐现有列）。

### 3.3 `PptGenerationService.run` 循环里插入取消检查

在 `while (current != PptState.SUCCESS)`（第 149 行）循环体最开头，`strategy.execute` 调用
**之前**：

```java
while (current != PptState.SUCCESS) {
    if (taskStore.isCancelRequested(taskId)) {
        taskStore.markCancelled(taskId, current);
        return;
    }
    PptGenerationStrategy strategy = strategiesByState.get(current);
    // ... 原有逻辑不变
}
```

这是**状态粒度**的协作式取消——和 PPT 现有的断点续传设计是同一个颗粒度（roadmap.md 已经把
"按状态粒度而不是子步骤粒度恢复"记成一条有意识的设计取舍），取消最坏情况下要等当前状态的
`strategy.execute` 跑完才生效（比如正在渲染 PPT，取消要等这次渲染完成），不是立即打断——
这个粒度选择和续传是一致的，不是这一票特有的妥协。

### 3.4 取消端点

```java
@PostMapping("/agent/v1/ppt/{taskId}/cancel")
public PptGenerationResponse cancel(@PathVariable long taskId) {
    String userId = currentUserId();
    if (userId != null && pptGenerationService.describe(userId, taskId).isEmpty()) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "PPT 任务不存在: " + taskId);
    }
    pptGenerationService.requestCancel(taskId);
    return toResponse(userId, taskId);
}
```

`PptGenerationService` 加一个 `requestCancel(long taskId)` 方法，内部调用
`taskStore.requestCancel(taskId)`——不直接操作 store，保持 Controller 只依赖 Service 这一层
（对齐现有的 `resume`/`status` 都是 Controller 直接调 Service 方法的风格）。

## 4. 刷新恢复：查询"我有哪些还在跑的任务"

**这一票只做后端接口，前端接线是另一张前端票的范围。**

```java
// DeepResearchController 新增
@GetMapping("/agent/v1/deepresearch/running")
public List<Long> runningTaskIds() {
    String userId = currentUserId();
    return taskRegistry.runningTaskIdsFor(userId);   // 需要给 registry 加这个查询方法
}
```

```java
// PptGenerationController 新增
@GetMapping("/agent/v1/ppt/running")
public List<Long> runningTaskIds() {
    String userId = currentUserId();
    return pptGenerationService.runningTaskIdsFor(userId);  // 需要给 service/store 加这个查询方法
}
```

两个查询方法都要求 `userId != null`（未登录直接返回空列表，不报错——刷新恢复对匿名访问没有
意义，不需要专门报错打断）。

## 5. Testing Decisions

- 越权测试：账号 A 不能 `cancel`/`status` 账号 B 的 DeepResearch 或 PPT 任务，返回 404
- 取消测试（PPT）：在某个状态中途调用 `requestCancel`，验证下一次状态转移前循环退出，
  `status` 最终为 `CANCELLED`，不是 `FAILED`
- 取消测试（DeepResearch）：调用 `cancel` 后 `future.isCancelled()` 为 true，`status` 返回
  `CANCELLED`；已经跑完的任务再调用 `cancel` 返回明确的"任务已结束，无法取消"而不是静默成功
- 刷新恢复测试：提交任务后立即查询 `running` 端点应该包含这个 `taskId`；任务完成/取消后再查询
  应该不再包含

## Out of Scope

- 前端页面的取消按钮、刷新后自动重连轮询
- DeepResearch 的真正协作式取消（子任务边界检查取消令牌）——这次只做 `Future.cancel(true)`
  尽力而为
- DeepResearch 持久化改造（重启丢失进行中任务的记录，这一票不改变这一点）
