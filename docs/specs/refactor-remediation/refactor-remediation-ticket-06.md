# Ticket 06：DeepResearchTaskRegistry 终态任务清理 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：给 `DeepResearchTaskRegistry`（`src/main/java/com/agenttrail/web/controller/DeepResearchTaskRegistry.java`）的
`tasks`/`handles` 两个 `ConcurrentHashMap` 加终态任务清理，防止不重启进程也无限增长。不涉及
把这个注册表改成持久化（那是 §2.8 目标架构 Phase 6 `Task` 统一模型要做的事，见
`refactor-blueprint.md` §2.3/§2.8），不涉及给 DeepResearch 补逐阶段进度（见 Ticket 07）。

## 1. 问题现状

证据见 `refactor-blueprint.md` §2.3 第三条："`DeepResearchTaskRegistry` 是纯内存
`ConcurrentHashMap`，重启丢失所有进行中任务的记录，且从不清理终态任务（不重启也会无限增长）"。

读实际代码确认（`DeepResearchTaskRegistry.java:24-87`）：

```java
final class DeepResearchTaskRegistry {
    private final AtomicLong taskIdSequence = new AtomicLong();
    private final Map<Long, DeepResearchTaskResponse> tasks = new ConcurrentHashMap<>();
    private final Map<Long, TaskHandle> handles = new ConcurrentHashMap<>();

    long start(String userId) {
        long taskId = taskIdSequence.incrementAndGet();
        tasks.put(taskId, DeepResearchTaskResponse.running(taskId));
        handles.put(taskId, new TaskHandle(userId, null));
        return taskId;
    }
    // complete()/fail()/cancel() 只 compute/put 已存在的 key，从不 remove
    // find()/belongsTo()/runningTaskIdsFor() 只读，也从不 remove
}
```

`start()` 是唯一的写入入口，`complete`/`fail`/`cancel` 三个终态转换方法都只 `compute`
已存在的 key，没有任何方法调用 `tasks.remove(...)`/`handles.remove(...)`。类注释里已经承认
"应用重启会丢失所有进行中任务的记录，这一点和 PPT 不对等，是有意识的范围取舍"——但没有提到
"不重启也会无限增长"这一点，属于类注释没覆盖到的真实缺口。

`DeepResearchController.java`（`web/controller/`）是唯一的调用方：`research()` 调
`taskRegistry.start(...)`，`status()`/`cancel()`/`runningTaskIds()` 都是只读或终态转换，没有
第二个地方持有这个 registry 的引用（`private final DeepResearchTaskRegistry taskRegistry = new
DeepResearchTaskRegistry();`，`DeepResearchController.java:44`，实例级别单例，生命周期等于应用
进程生命周期）。

## 2. 方案

**先验证结论**：`start()` 是唯一保证会被高频调用的方法（每次发起一个 DeepResearch 请求都会
调一次），`complete()`/`fail()` 只在对应任务结束时各调一次，`status()`（`find()`）是前端轮询
高频调用但只读。给 `start()` 顺手清理一批过期条目，比另开一个定时任务简单——不需要引入
`@Scheduled`/新线程，复用已经在被调用的入口，且天然保证"活跃使用中的注册表才会触发清理，
没有流量时也没必要清理"。**这是"先验证"后判断影响面最小的时机，不是唯一正确答案**——如果
未来 `start()` 调用频率变得很低（比如整个服务几乎没有新请求），过期任务会在被清理前多留一会，
不影响正确性，只是清理不够及时，可接受。

```java
final class DeepResearchTaskRegistry {

    /** 前端轮询间隔 1.5s（见 frontend/src/views/ChatView.vue:127 pollUntilTerminal 的
     *  intervalMs 默认值，DeepResearch/PPT/Golden 评测三处轮询共用同一套写法）——终态任务
     *  至少要在结果对应的最后一次轮询之后还能被读到。10 分钟是留了远超正常轮询间隔的余量，
     *  覆盖网络抖动、浏览器标签切到后台被节流等场景，不是掐着 1.5s 卡点设的下限。 */
    static final Duration RETENTION = Duration.ofMinutes(10);

    private final Map<Long, DeepResearchTaskResponse> tasks = new ConcurrentHashMap<>();
    private final Map<Long, TaskHandle> handles = new ConcurrentHashMap<>();
    // 新增：记录进入终态的时间戳，用于判断是否超过保留期
    private final Map<Long, Instant> terminalAt = new ConcurrentHashMap<>();

    long start(String userId) {
        evictExpired();
        long taskId = taskIdSequence.incrementAndGet();
        tasks.put(taskId, DeepResearchTaskResponse.running(taskId));
        handles.put(taskId, new TaskHandle(userId, null));
        return taskId;
    }

    private void evictExpired() {
        Instant cutoff = Instant.now().minus(RETENTION);
        terminalAt.entrySet().removeIf(entry -> {
            if (entry.getValue().isAfter(cutoff)) {
                return false;
            }
            tasks.remove(entry.getKey());
            handles.remove(entry.getKey());
            return true;
        });
    }
}
```

`complete()`/`fail()`/`cancel()` 三处进入终态的地方都要顺手写一次 `terminalAt.put(taskId,
Instant.now())`——**先验证**：读现有 `complete`/`fail`/`cancel` 的 `compute`/`put` 写法，确认
在哪一行之后追加不会打乱它们"已取消的任务不能被后来的 complete/fail 覆盖状态"这条既有语义
（`DeepResearchTaskRegistryTest.java` 已经有一个测试专门锁住这条语义，改动后必须继续通过）。

`evictExpired()` 放在 `start()` 里执行，代价是每次发起新任务都要扫一遍 `terminalAt`（预期
条目数量在正常运行的 DeepResearch 场景下是"最近 10 分钟内完成的任务数"级别，不会很大，
`ConcurrentHashMap.entrySet().removeIf(...)` 的开销可以接受）。如果未来发现 `start()` 调用
频率过低导致清理不及时，或者 `terminalAt` 规模变得可观，再考虑换成 `@Scheduled` 定时任务——
这一票先用最简单能满足需求的方案。

## 3. 边界情况

- `cancel()` 触发的 `CANCELLED` 状态同样要计入 `terminalAt`（当前 `cancel()` 只在真正成功取消
  时 `tasks.put(taskId, DeepResearchTaskResponse.cancelled(taskId))`，这个分支也要写
  `terminalAt`）。
- `runningTaskIdsFor(userId)` 只过滤 `status == RUNNING` 的条目，清理终态任务不影响这个方法
  的正确性。
- 清理时机在 `start()` 里，意味着"没有新任务发起"的空闲期间旧终态任务会一直留着不清——这是
  刻意接受的简化，不是需要修的 bug：DeepResearch 是低频操作（分钟级单次调用），不会长期堆积
  到内存问题的量级，真正需要卡住的是"高并发场景下不重启也无限增长"这条，`start()` 高频触发
  正好覆盖这种场景。

## 4. Testing Decisions

- 验证任务完成后一段时间内仍能查到状态：`complete(taskId, report)` 后立即 `find(taskId)`
  应该返回 `SUCCESS`；模拟"刚好在保留期内"（比如用可控的 `Clock`/时间戳注入让测试不用真的等
  10 分钟）再次 `find(taskId)` 仍应命中。
- 验证超过保留期后条目被清理：提交大量任务（比如循环 `start()` + `complete()` 几百次），
  把 `terminalAt` 的时间戳构造成"已超过保留期"（测试里 `DeepResearchTaskRegistry` 需要一个
  测试专用构造函数或包可见的时间戳注入点，避免真的 `Thread.sleep(10分钟)`——参照
  `ToolCallExecutor` 已有的"测试专用注入短 timeout 的构造函数"先例，`ToolCallExecutor.java:89`
  的 `roundTimeout` 参数就是同样目的的先例），再触发一次 `start()`，断言 `tasks`/`handles`
  的 size 回落到只剩未过期条目。
- `DeepResearchTaskRegistryTest.java` 现有的
  `cancellationKeepsOwnershipAndPreventsLateCompletionFromOverwritingCancelledState` 测试必须
  继续通过——它验证的"取消后 complete 不能覆盖 CANCELLED 状态"这条语义不能被清理逻辑破坏。

## Out of Scope

- 把 `DeepResearchTaskRegistry` 改造成持久化/可跨重启恢复——这是 Phase 6（`refactor-blueprint.md`
  §2.8，Ticket 17）Task 统一模型要做的事，这一票只解决"不重启也无限增长"，不解决"重启丢失"。
- 定时清理任务（`@Scheduled`）——先用 `start()` 时机顺手清理，除非后续发现调用频率不足以
  支撑清理及时性，再单独评估要不要加。
- DeepResearch 的进度可见性（`currentStep` 字段）——见 Ticket 07，两票各自独立。
