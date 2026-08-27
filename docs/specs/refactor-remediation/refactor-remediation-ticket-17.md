# Ticket 17（Phase 6）：DeepResearch 改造为 Workflow+Task — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 15](refactor-remediation-ticket-15.md)。
> 可与 [Ticket 16](refactor-remediation-ticket-16.md)、[Ticket 18](refactor-remediation-ticket-18.md) 并行——三票
> 各自迁移互不重叠的能力包，共同前提只是 Ticket 15 的 Run/Task/Checkpoint/Event 基础设施。Blocks
> [Ticket 20](refactor-remediation-ticket-20.md)（多 Agent/Skills/MCP/A2A 需要先有一个跑通的 Workflow 范例）。
> 取代 [Ticket 07](refactor-remediation-ticket-07.md) 的临时方案——Ticket 07 给 `DeepResearchTaskRegistry`
> 加的 `currentStep` 字段在这一票落地后可以删除，改用正式的 `WorkflowEvent`/`EventEnvelope`；如果 Ticket 07
> 已经先合并，这一票负责删除它引入的字段和对应的前端装饰逻辑改回真绑定，不是绕开它重新做一遍。
> **与 Ticket 16 协调**：会话历史写入统一挪到 `conversation.application` 的同一个 port（Ticket 16 定义），
> 命名/方法签名以 Ticket 16 落地的实际接口为准，这一票不要另起一套。

## 0. 范围边界

**2026-08-11 已定案：不引入通用 `WorkflowDefinition<S>`/`WorkflowNode`/`WorkflowEngine` 抽象**（原
`refactor-blueprint.md` §5.2/§2.8 提出的设计已废弃，不再作为这一票的目标形状）。理由见本文档
之前讨论过的两点，这里只记结论：① 横向读过的 DeepResearch 参考实现本身就是一段写死在具体类里的
`for` 循环，没有通用工作流引擎；② AgentTrail 自己的 PPT（`PptState` 枚举 + `Map<PptState,
PptGenerationStrategy>` + `PptGenerationService` 显式驱动）已经验证过"具体状态枚举 + 显式代码"
这条路径能跑，且是目前唯一两个"多步骤可恢复"的场景（DeepResearch、PPT），还没到需要抽象出第三种
形状的地步（三次法则）。

**这一票改成**：把 `DeepResearchService` 现有的计划-执行-批判循环保留为具体的 Java 方法调用，
不套进任何通用节点/引擎接口，只在阶段之间插入检查点（复用 Ticket 15 的 `CheckpointStore`，做法
复用 `PptGenerationService` 每步之后落 checkpoint 的模式）；把 `Semaphore` 并发控制换成
`ConcurrencyPolicy`；给每个搜索任务加独立幂等键；把研究证据/报告落 Artifact/Repository 而不是纯
内存传递；新建 `DeepResearchTaskWorker`（一个专门为 DeepResearch 写的具体驱动类，不是某个通用引擎的
实例化），`DeepResearchController` 收缩成只提交任务；`/deepresearch/{taskId}/events` SSE + 标准化
`/cancel`（取消机制复用 `AgentTaskManager`，见第 7 节）；会话历史写入挪到完成事件处理器（含取消
记录，见第 8 节）；阶段级恢复测试。**不做**：PPT/Chat 的迁移（Ticket 16/18）、多 Agent 编排
（Ticket 20）、真正接入除 Tavily 外的其它搜索源、批判循环算法本身的改动（`planExecuteCritiqueLoop` 的
业务逻辑原样保留，只是换了个执行骨架）、任何形式的通用 Workflow 抽象设计。

## 1. 先验证：`DeepResearchService.java` 现状核实

开工前完整读了 `capability/deepresearch/DeepResearchService.java`（444 行）、`DeepResearchController.java`、
`DeepResearchTaskRegistry.java`、`DeepResearchConfig.java`，把真实用到的中间状态和现有行为如实列出来，
后面第 2-3 节的 `DeepResearchState`/节点划分都是照这份清单对应，不是凭空设计：

- **真实用到的中间状态**（`planExecuteCritiqueLoop` 方法体，第 180-211 行）：`allResults`
  （`List<TaskResult>`，跨轮次累加，是 API 返回给外部消费者的原始结构化结果，不受压缩影响）、
  `researchContext`（`List<Message>`，喂给 `critique`/`summarize` 的累积上下文，可能被
  `ContextCompactor` 原地压缩）、`previousFeedback`（`String`，可空，批判不通过时的反馈拼进下一轮
  `generatePlan` 的输入）、当前 `round`（`int`，1..`maxCritiqueRounds`）。这四个是 `DeepResearchState`
  必须覆盖的核心字段，其余（`topic`/`question`）是每次节点执行的输入/输出，不是循环状态本身。
- **需求澄清是独立于批判循环的前置步骤**：`research(String question)`（第 139-145 行）先调
  `plainExecutor.call(CLARIFICATION + question)`，`needsMoreInfo(...)` 判定不通过直接返回
  `DeepResearchReport.needsClarification(...)`，不进入 `proceedFromTopic`。`continueAfterClarification`
  （第 153-159 行）是另一个入口，跳过 `needsMoreInfo` 判断直接进 `proceedFromTopic`——这两个入口对应
  目标图里 `Clarify` 节点的"通过"和"不通过后用户回复重新进入"两条边，不是同一个节点的两次调用。
- **重试发生在任务级，不是节点级**：`executeTask`（第 398-418 行）内部自己有一个 `for (attempt = 1;
  attempt <= maxTaskRetries + 1; ...)` 循环，失败了立即在同一个方法里重试，不通过异常往外抛再由
  上层重试——这意味着"重试"这件事目前完全在 `FanOut` 节点内部私有实现，不是 Workflow 引擎层面的
  节点重试机制。这一票要把这层私有重试逻辑和 Ticket 15 的 `RetryClass`/节点重试机制的关系想清楚：
  是把 `executeTask` 的重试循环整个搬进 `SearchTask` 节点内部保留现状（简单，但节点重试和任务内重试
  变成两层嵌套语义），还是拆成"节点失败即重试整个节点、重试次数由 Workflow 引擎控制"（更贴合目标
  架构，但要改变现有"部分任务失败不阻塞其余任务"的隔离特性）。**这一票选择前者**——`executeTask`
  的重试循环原样保留在 `SearchTask` 节点实现内部，不推给 Workflow 引擎的节点级重试机制，理由是
  `FanOut` 展开出的多个 `SearchTask` 之间本来就要求相互独立（一个任务耗尽重试不阻塞其它任务/其它层，
  第 392-396 行类注释明确写了这一点），如果改成节点级重试，一个任务的失败会被当成整个 `FanOut` 节点
  失败处理，破坏这条已经验证过的隔离行为。
- **幂等/重复计费风险核实**：`executeTask` 每次重试都是重新调一次 `searchExecutor.call(...)`——
  当前实现里，一次 `call()` 抛异常意味着这次调用确实没有拿到可用结果（`AgentLoopExecutor.call()`
  的语义是"阻塞到完成或抛异常"，不存在"已经执行了外部副作用但客户端没收到结果"这种部分成功态），
  所以在**当前同步执行模型下**，重试本身不会对同一个搜索任务产生两次计费的资源浪费问题（重试就是
  重新发起，failed 的那次调用没有产生需要计费的完整调用结果）。**真正的重复执行风险出现在这一票
  引入的 Worker+Checkpoint 模型之后**：如果一个 `SearchTask` 节点已经成功拿到结果、但 Worker 在把
  结果写入 checkpoint 之前崩溃，重启后按 checkpoint 恢复会重新执行这个节点，这次会对外部搜索
  API/工具产生真正的重复调用和重复计费——这是幂等键要解决的问题，不是"现状已经有 bug"，是"改成
  可恢复 Worker 之后如果不加幂等键会引入新 bug"，第 5 节据此设计。
- **取消语义现状**：`DeepResearchController.cancel()`（`web/controller/DeepResearchController.java:103-121`）
  调 `DeepResearchTaskRegistry.cancel()`，本质是 `Future.cancel(true)` 中断跑 `research(...)` 的后台
  线程；`executeLayerConcurrently`（`DeepResearchService.java:365-390`）内部用
  `Executors.newVirtualThreadPerTaskExecutor()` 的 try-with-resources，外层线程被中断后阻塞在
  `future.get()` 的调用会抛 `InterruptedException`，包装成 `IllegalStateException` 往上抛，最终被
  `DeepResearchController` 的 `catch (RuntimeException failure)` 捕获记为 `FAILED`——**不是** `CANCELLED`，
  除非 `taskRegistry.cancel()` 的 `Future.cancel(true)` 调用成功（第 62-66 行会在取消成功时立即把状态
  覆写成 `DeepResearchTaskResponse.cancelled(taskId)`，覆盖掉后台线程稍后写入的 `FAILED`）。这一票在
  第 6 节标准化 `/cancel` 端点时，验收标准是"新实现取消后任务终态必须是 `CANCELLED`，且已经在跑的
  搜索任务确实停止（不是继续跑完只是前端看不到结果）"——这比现状"取消成功时靠状态覆写恰好表现为
  `CANCELLED`、底层线程可能还在收尾"更明确，是这一票要求的真正改进点，不能只是把现有行为原样照搬。

## 2. `DeepResearchState`/`DeepResearchStage` 与显式驱动

**不定义通用节点接口**——新建 `capability.deepresearch.application.DeepResearchWorkflow`（类名沿用，但它是一个
具体类，不 `implements` 任何通用 Workflow 接口）作为 `DeepResearchService` 的迁移落点，内部结构沿用
`PptGenerationService` 的形状：一个 `DeepResearchStage` 枚举 + 一组具体私有方法（不是通用 `Node` 实现）+
一个显式的驱动方法负责"当前在哪个阶段、下一步调哪个方法、调完写不写 checkpoint"。

```java
public enum DeepResearchStage {
    CLARIFY, GENERATE_TOPIC, PLAN, FAN_OUT, MERGE_EVIDENCE, CRITIQUE, SYNTHESIZE, REPORT_ARTIFACT
}
```

和 `PptState`（`INIT/REQUIREMENT/SEARCH/.../SUCCESS/CANCELLED`）不同的是，DeepResearch 的阶段之间
不是单纯的线性表——`Critique` 有 `passed`/`!passed` 两条分支（分别去 `Synthesize`/回 `Plan`），
`Plan` 会被 `Critique` 的失败分支重复进入。`PptGenerationService` 靠 `Map<PptState,
PptGenerationStrategy>` 做纯线性驱动就够用；这里驱动方法需要显式的 if/switch 分支来处理这两条
回边和"达到 `maxCritiqueRounds` 强制进 `Synthesize`"这条兜底，而不是硬套一个假装是线性表的数据
结构——分支逻辑摆在一个具体的 Java 方法里，比摞进一个通用引擎的条件路由配置更直接、更容易读。

`DeepResearchState` 按第 1 节枚举的真实字段设计：

```java
public record DeepResearchState(
        String question,                 // 用户原始问题（含追问续接后拼接好的完整问题）
        String clarifyingQuestion,        // Clarify 节点产出，非 null 时流程在此终止等待用户回复
        String topic,                     // GenerateTopic 节点产出
        int round,                        // 当前批判轮次，1..maxCritiqueRounds
        String previousFeedback,          // 上一轮批判反馈，null 表示第一轮或已通过
        List<TaskResult> allResults,      // 跨轮次累加，落 Artifact 前的原始结构化结果
        List<DeepResearchContextEntry> researchContext, // 替代原来直接持有 Spring AI Message 的列表，
                                           // 见下方"先验证"关于 Message 类型泄漏的说明
        String report) {                  // Synthesize 节点产出，ReportArtifact 节点消费
}
```

**先验证/纠偏**：原实现的 `researchContext` 直接是 `List<org.springframework.ai.chat.messages.Message>`
（`DeepResearchService.java` 第 182/220-231 行），把 Spring AI 类型嵌进了业务状态对象里——这正是
`refactor-blueprint.md` §5 迁移映射表规定业务层禁止依赖的类型（"业务禁止依赖：`AgentLoopExecutor`、
Spring `ChatModel`、Web DTO"隐含不应该直接持有 Spring AI 消息类型）。这一票把 `researchContext` 的元素
类型改成 `DeepResearchContextEntry`（自定义的 `record(String taskId, String label, String content, boolean
isCritiqueFeedback)` 或等价形状），`taskResultToContextMessage`/`critiqueFeedbackContextMessage` 两个方法
（第 220-231 行）的职责保留但产出类型改掉；`ContextCompactor` 需要 `List<Message>` 的地方，在
`ModelGateway`（Ticket 13 契约）适配层做一次转换，不让 `DeepResearchState` 本身携带 Spring AI 类型——这是
Ticket 13"切断 Spring AI 泄漏"这条原则在 Phase 6 的具体延续，不是这一票重新发明的新规则。

`DeepResearchStage` 逐个对应现有方法，方法体原样搬过去，不重写业务逻辑，只是从"一个大循环方法里的
一段代码"变成"驱动方法按当前 stage 调用的一个具体私有方法"：

| 阶段 | 现有方法体来源 | 备注 |
|---|---|---|
| `CLARIFY` | `research()` 第 139-145 行（`plainExecutor.call(CLARIFICATION+question)` + `needsMoreInfo`） | 产出 `clarifyingQuestion` 非空时驱动方法在此阶段后暂停，等待外部输入（`continueAfterClarification` 的调用方式，见第 4 节） |
| `GENERATE_TOPIC` | `proceedFromTopic()` 第 162-163 行 | |
| `PLAN` | `generatePlan()`（第 301-331 行）+ `applyBreadthCap()`（第 333-340 行） | 批判不通过时带着 `previousFeedback` 重新进入这个阶段，对应 `Critique` 失败回到 `Plan` 那条边——驱动方法里是一个显式的 `case CRITIQUE -> ... ? SYNTHESIZE : PLAN` 分支，不是数据驱动的表 |
| `FAN_OUT` | `executeLayered()`（第 348-362 行）+ `executeLayerConcurrently()`（第 365-390 行）+ `executeTask()`（第 398-418 行，含任务级重试） | 按 order 分层这件事本身保留；层内并发度改由 `ConcurrencyPolicy` 控制（见第 3 节），不再是自己 new 一个 `Semaphore` |
| `MERGE_EVIDENCE` | `executeLayered()` 里 `allResults.addAll(...)`/`researchContext.add(...)`/`renderLayerContext()`（第 353-359、420-427 行） | 从"和 FAN_OUT 同一个方法里顺手做"拆成独立阶段，职责更清楚：FAN_OUT 只管拿到 `List<TaskResult>`，MERGE_EVIDENCE 只管把它并入累积状态 |
| `CRITIQUE` | `critique()`（第 250-284 行） | `passed` 分支进 `SYNTHESIZE`，`!passed` 且未达 `maxCritiqueRounds` 上限回到 `PLAN`，达到上限直接进 `SYNTHESIZE`（对应原 `planExecuteCritiqueLoop` 第 196-199 行的轮数上限提前退出逻辑） |
| `SYNTHESIZE` | `summarize()`（第 429-435 行） | |
| `ReportArtifact` | 无对应现有方法——这是新增职责，见第 5 节 | 把 `report`/`allResults`/`topic` 落 Artifact/Repository，不是只放进返回对象内存传递 |

## 3. 并发控制：`Semaphore` 换成 `ConcurrencyPolicy`

**现状**：`executeLayerConcurrently`（第 365-390 行）每次调用都 `new Semaphore(maxConcurrentTasksPerLayer)`
+ `Executors.newVirtualThreadPerTaskExecutor()`，并发上限是这个 `DeepResearchService` 实例级别的固定配置
（`agenttrail.deepresearch.max-concurrent-tasks-per-layer`，默认 3），不区分租户/用户，也不是全局背压——
两个用户同时各发起一次 DeepResearch，会各自起一套虚拟线程 + `Semaphore(3)`，互不感知对方的存在。

**这一票要做的**：`FanOut` 节点执行层内并发时改为向 Ticket 15 提供的 `ConcurrencyPolicy` 挂载点申请执行
配额，而不是自己 new 一个 `Semaphore`。**先验证**：具体挂载点的接口形状（是 `ConcurrencyPolicy.acquire(tenantId,
capability, permits)` 这样的显式申请，还是 Workflow 引擎在节点执行前自动按策略限流、节点实现完全无感）
以 Ticket 15 落地后的实际接口为准，这份文档写票时 Ticket 15 还没有代码，不假装知道确切签名。**这一票的
验收标准**是"同一租户/用户的 DeepResearch 并发搜索任务数受策略控制，且策略维度是 tenant/capability 而
不是进程级固定配置"——单层内 3 个并发任务这个具体数值可以保留作默认值，不是这一票要改变的业务参数。

## 4. 每个搜索任务的独立幂等键

按第 1 节"先验证"确认的真实风险场景（Worker 崩溃在"节点成功但 checkpoint 未写"之间的窗口）设计：
`FanOut` 展开出的每个 `SearchTask` 节点执行实例，用 `taskId + "-" + researchTask.id() + "-" + round`
（`ResearchTask.id()` 是计划里任务的稳定标识，第 1 节已确认它在 `ResearchPlan`/`ResearchTask` 里存在，
`round` 区分批判循环不同轮次里可能重新生成的同名任务）构造幂等键，注册进 Ticket 15 提供的幂等机制
（具体是 `IdempotencyStore` 还是 Checkpoint 自带的按节点实例去重，以 Ticket 15 实际落地为准）。效果：
Worker 重启后如果发现某个 `SearchTask` 实例的幂等键已经有一条成功记录，直接复用结果，不重新调用
`searchExecutor`，避免对外部搜索 API 的重复计费调用。

## 5. 研究证据/报告落 Artifact/Repository

**现状**：`DeepResearchReport`（`allResults`/`report`/`researchTopic`）只是一个纯内存 record，`research(...)`
调用返回给 `DeepResearchController` 之后就没有任何持久化——`agent_session.timeline` 里存的是
`CapabilityConversationService.recordSuccess` 写入的最终摘要（`answer`/`payload`），不是完整的检索证据链，
应用重启后这份 `DeepResearchReport` 彻底丢失，不像 Ticket 15 的 `RunRepository`/`CheckpointStore` 那样可
按 `runId` 重新查询。

**这一票要做的**：`MERGE_EVIDENCE` 阶段每次合并结果后，把 `allResults` 的增量写入 Ticket 15 的
`CheckpointStore`（阶段级 checkpoint，天然支持崩溃恢复）；`REPORT_ARTIFACT` 阶段把最终 `report` 连同
`allResults`/`topic` 一起写入 Ticket 15 的 `RunRepository`（或专门的 `EvidenceRepository`，对应
`refactor-blueprint.md` §2.8 迁移映射表"业务允许依赖：`AgentRuntimePort`、`TaskCoordinator`、
`EvidenceRepository`"），产出一个可按 `runId`/`taskId` 查询的 Artifact 引用，而不是只存在于一次 HTTP
响应或一次内存对象里。`DeepResearchReport` 这个 DTO 保留作为 HTTP 层的展示形状，但它的数据来源改成
"查询 Artifact/Repository"，不是"驱动方法跑完直接把内存对象传回来"。

## 6. `DeepResearchTaskWorker` + `DeepResearchController` 收缩

新建 `capability.deepresearch.application.DeepResearchTaskWorker`（一个具体类，消费 Ticket 15 的任务队列/
触发机制，驱动 `DeepResearchWorkflow` 从当前 checkpoint 继续跑到下一个阶段或整个流程完成）。
`DeepResearchController` 改造后只做：校验请求、调用 `AgentRuntimePort`/`TaskCoordinator`（具体门面
以 Ticket 15 为准）提交一个新的研究任务、返回 `{runId, taskId}`（对齐 `refactor-blueprint.md` §2.8 的 Task API：
`POST /agent/v1/runs → 202 {runId,taskId}`，DeepResearch 可以在这个通用 Task API 之上，也可以保留
`/agent/v1/deepresearch` 这个专用路径直接转发，这一票不强制要求把 URL 也切换到通用 `/runs`，取决于
Ticket 15 是否已经把通用 Task API 作为唯一入口——**先验证**这一点，如果 Ticket 15 没有强制要求所有能力
包统一走 `/agent/v1/runs`，这一票保留 `/agent/v1/deepresearch` 这个路径不变，只改内部实现）。

**现有轮询端点降级为兼容层**：`GET /agent/v1/deepresearch/{taskId}` 继续存在（前端可能还没切到 SSE），
内部实现改成查询 `RunRepository`/`CheckpointStore` 的最新状态，不再依赖 `DeepResearchTaskRegistry` 这个
纯内存 `ConcurrentHashMap`——`DeepResearchTaskRegistry` 这一票之后整体删除，它解决的"重启丢失任务记录"
问题被 Ticket 15 的持久化任务模型彻底取代，不是这一票要保留的兼容对象。

## 7. `/deepresearch/{taskId}/events` SSE + 标准化 `/cancel`

用 Ticket 15 的 `RunEventStore`/`EventEnvelope` 实现新端点 `GET /agent/v1/deepresearch/{taskId}/events`
（SSE，支持 `Last-Event-ID`/`afterSequence` 重放，事件类型对应 `ResearchNode` 的开始/完成/失败——
"研究请求立即返回 taskId"之后，前端订阅这个端点就能看到 `Clarify`/`Plan`/`FanOut` 每个节点真实的进度，
这正是 `refactor-blueprint.md` §2.7 指出的"DeepResearch 是唯一黑盒"问题的正式解法，取代 Ticket 07 的
临时 `currentStep` 字段方案）。

### 7.1 现状取消机制不可靠，不能原样套进新架构（2026-08-11 核实）

`DeepResearchController.cancel()` 现在调用 `DeepResearchTaskRegistry.cancel(taskId)` →
`handle.future().cancel(true)`——这是 `ExecutorService.submit()` 返回的 `Future` 的线程中断，
**不是 Reactor 原生的 `Disposable.dispose()` 取消信号**。这条路径已经被证明不可靠：
`SynchronousLlmCallTest.java`（踩坑点 #92，源自 2026-08-06 一次真实的 DashScope 连接卡住 236
秒不返回的事故）明确写着——

> "Reactor 对 boundedElastic 上的任务取消是 `Future.cancel(true)`——确实会发一次线程中断，
> `Thread.sleep()` 这种可中断阻塞能响应它；但 OkHttp 的阻塞 socket 读取通常不是可中断的，
> 所以生产事故里那条真实卡住的调用不会因为这个中断就真的解除阻塞"

也就是说：用户点取消后，`DeepResearchTaskRegistry` 里的任务状态会立刻变成 `CANCELLED`（注册表层面
没问题），但如果此刻正好卡在 `plainExecutor.call()`/`searchExecutor.call()` 内部等大模型网络响应
（循环里大部分时间都在这个状态），**这次具体的 HTTP 调用不保证真的会断**，会在后台继续跑到完成或
自己超时，用户看到的"已取消"和实际发生的事情不一致。`executeLayered` 里同层并发的多个
`SearchTask`（`Executors.newVirtualThreadPerTaskExecutor()` 起的虚拟线程）情况更糟——外层线程被
中断后只是 `future.get()` 提前返回，并不保证已经在跑的那几个虚拟线程任务真的停下来。

### 7.2 正确的机制已经存在于本项目——`AgentTaskManager`，只是 DeepResearch 没用上

`loop/task/AgentTaskManager.java` 用的是 `Disposable`/`disposable.dispose()`（Reactor 原生取消
信号，不是线程中断），`/agent/v1/chat` 主对话路径走的就是这套机制，而且已经是跨实例设计（Redis
锁抢注册归属 + broadcaster 跨实例广播停止）——这和横向调研的参考实现（"多实例Agent任务管理改造"
一文描述的 Redis SETNX + Pub/Sub 停止广播）是同一个思路，本项目自己已经做对了一次，只是
`DeepResearchService`/`DeepResearchController` 完全没有复用它，另外搭了一套更弱的
`DeepResearchTaskRegistry` + `Future.cancel(true)`。

**这一票的取消机制不新设计、不引入 `AgentRuntimePort.cancel(runId, reason)` 这类新接口**，直接让
DeepResearch 的任务生命周期注册到 `AgentTaskManager`（和主对话共用同一套跨实例协调基础设施）。这要
求 `DeepResearchTaskWorker` 执行时至少在最外层保留一个可以被 `AgentTaskManager` 持有并 `dispose()` 的
订阅，不能是现在这种"整个 `research()` 方法在一次同步阻塞调用里跑完，外部只能靠 `Future` 包一层"
的写法——具体是把 `DeepResearchService` 的执行入口从 `.call()`（阻塞）改造成保留一个可释放的
`Disposable`/`Flux` 句柄，细节和 Ticket 15 的 Task/Checkpoint 设计一起定，但复用 `AgentTaskManager`
这条方向在这一票就要锁定，不留到实现时再选。

**验收标准**：取消后任务终态必须稳定落为 `CANCELLED`（不依赖"状态覆写恰好覆盖掉后台线程稍后写入的
FAILED"这种时序竞争），且正在执行的 `SearchTask` 节点通过 `dispose()` 真正停止，不是继续跑完只是
轮询/SSE 看不到结果。`DeepResearchTaskRegistry` 这个类在这一票里应该被删除，不是被保留成"新旧两套
注册表并存"。

## 8. 会话历史写入挪到完成事件处理器

**现状**：`DeepResearchController.research()`（`web/controller/DeepResearchController.java:59-85`）
在后台线程的 lambda 内部直接调用 `conversationService.recordSuccess(...)`/`recordFailure(...)`——HTTP
Controller 既负责提交任务，又负责在任务完成时写会话历史，职责没有分开。

**这一票要做的**：会话历史写入挪到 `DeepResearchWorkflow` 的完成事件处理器（`ReportArtifact` 节点执行完毕后，
或 Workflow 引擎的"Run 完成"生命周期钩子），调用 Ticket 16 定义的 `conversation.application` 统一 port
（方法名/参数以 Ticket 16 落地的接口为准，这一票不重复定义）。`DeepResearchController` 之后完全不感知
"什么时候该记一条会话历史"这件事——这是"HTTP 层职责收缩"的具体体现之一，不只是"Controller 不跑业务逻辑"
这句话，是连"业务完成后要做什么收尾"都不该由 Controller 编排。

### 8.1 取消必须落一条独立记录，不能沿用现有的"失败"路径（2026-08-11 核实的现状缺口）

**先验证的结论**：`CapabilityConversationService.java` 现在只有 `recordSuccess`/`recordFailure` 两个
方法，没有 `recordCancel`。现状代码里，`DeepResearchController.research()`（59-85 行）的
`catch (RuntimeException failure)` 分支在任务被取消、线程中断抛出异常时也会走到这里，调用
`recordFailure(...)`——**用户主动取消的任务，会话历史里被记成"失败，原因是中断异常"，不是"用户
已取消"**，前端历史列表和真实发生的事情对不上。

这一票要新增一条独立的取消记录路径（`conversation.application` port 上加一个语义清晰的方法，比如
`recordCancelled(...)`，不是复用 `recordFailure` 换个错误文案），并且要带上**取消时已经拿到的部分
成果**，不是空记录：

- 已经跑完的层/已经拿到的 `TaskResult`（哪怕只有第一层的检索结果）
- 取消发生时 `DeepResearchState` 处在哪个节点（`Clarify`/`Plan`/`FanOut`/`Critique`/`Synthesize` 之一）

这里直接复用 AgentTrail 已有的会话持久化能力：`agent_session.timeline` 已用于保存结构化的
思考、正文和工具调用事件，因此消息记录可以携带进度快照，而不必只保存终态文本。取消记录应该往
这个字段里追加一条能反映
"跑到哪一步、已经查到什么"的条目，复用 `architecture.md` 已经定义的 `{"type":"StageOutput",
"stage":"research",...}` 结构，不新发明一套格式。前端历史列表因此能看到"这次研究被取消了，
当时已经完成到第 N 层检索"，而不是一条和其它失败记录混在一起、看不出区别的"failed"。

## 9. 节点级恢复测试

新增测试覆盖：在 `FanOut`（某个 `SearchTask` 执行到一半）、`Critique`、`Synthesize` 三个节点前后各模拟一次
"进程崩溃"（测试里表现为：Worker 执行到某个节点后中途终止，不写入该节点的 checkpoint，用新的 Worker 实例
从 `CheckpointStore` 记录的最近状态重新启动），验证：

- 恢复后 Workflow 从正确的节点继续，不重跑已经成功 checkpoint 过的节点。
- `FanOut` 场景下，已经成功的 `SearchTask` 凭幂等键不被重复执行（对应第 4 节），未完成的任务重新执行。
- `Critique`/`Synthesize` 节点重跑时使用的输入（`researchContext`/`allResults`）和崩溃前一致，不因为
  恢复流程丢失或重复累加。

## 10. Testing Decisions

- `DeepResearchWorkflow` 单元测试：`Clarify` 两条分支（信息充分直接进 `GenerateTopic`；不充分返回
  `clarifyingQuestion` 并暂停）；`Critique` 通过/不通过两条边；批判轮数到达 `maxCritiqueRounds` 时强制进
  `Synthesize`（对应原 `planExecuteCritiqueLoop` 第 196-199 行的行为，不能迁移后丢失这条兜底）。
- `FanOut`/`SearchTask` 测试：单个任务失败重试耗尽后不阻塞同层其余任务（保留现有 `executeTask` 的隔离
  特性）；同一批任务的幂等键在两次相同输入下一致，在不同 `round` 下不同。
- 并发策略测试：验证 `ConcurrencyPolicy` 挂载点确实生效——同一租户超过配额的并发搜索任务被排队而不是
  全部同时发起（具体断言方式取决于 Ticket 15 落地的 `ConcurrencyPolicy` 接口）。
- 节点级恢复测试：见第 9 节，是这一票的测试重点。
- SSE/取消测试：`/deepresearch/{taskId}/events` 断线重连后凭 `afterSequence` 不丢事件、不重复事件；取消请求
  发出后任务终态确定性地落为 `CANCELLED`，且能验证正在执行的搜索任务真的停止（不是"看起来取消了但底层
  还在跑"）。
- 集成测试延续项目一贯约定，起真实 MySQL/Redis（Testcontainers），不用 H2 替代。

## 验收标准（沿用 `refactor-blueprint.md` §6 Phase 6）

- Research 请求立即返回 `taskId`。
- Worker 重启后可从最近 checkpoint 继续。
- 用户会话不出现内部子调用（延续现有 `forInternalOrchestration` 不挂 `persistenceHook` 的行为，见
  `AgentLoopExecutorFactory.java` 第 436-454 行的类注释——这条现有行为在迁移后必须继续成立，不是这一票
  新引入的要求）。

## Out of Scope

- Chat/PPT 的迁移（Ticket 16/18）。
- 批判循环算法本身的改动（判定标准、prompt 内容）——只搬运执行骨架，不重新设计业务逻辑。
- 除 Tavily 外接入其它搜索源。
- 把 `/agent/v1/deepresearch` URL 强制切换到通用 `/agent/v1/runs`——除非 Ticket 15 已经要求所有能力包
  统一入口，否则保留专用路径。
- 多 Agent 编排、Skills/MCP/A2A 接入 DeepResearch 节点——Ticket 20 范围。
- **取消后恢复执行**（用户主动点了取消，之后想接着跑刚才没跑完的部分）——2026-08-11 调研过
  OpenAI/Mistral/Gemini 三个 Deep Research 产品后确认不做：OpenAI 没有"暂停纠偏"选项，方向跑偏
  只能整个停掉重来；Mistral 官方明确"canceling a Deep Research is an irreversible action, and a
  canceled task cannot be resumed"；AgentTrail 自己的 `AgentTaskManager`（本票 §7.2 复用的机制）
  类注释也写着"目前只有 `stopTask` 这一种'硬停止'语义（丢弃全部运行时状态）"——三方证据一致指向
  取消就该是不可逆的硬停止，用户取消的动机通常是"这个方向不对"，恢复回一个已经被否定的执行路径
  没有意义，真要继续应该重新发起一次。**不要把这条和下面这条混淆**：
- （**这条不是 Out of Scope，是本票范围内已经覆盖的能力，写在这里只为和上面一条划清界限**）
  "客户端断连后重新接上一个仍在服务端运行的任务"——这和"取消后恢复"是两回事：任务没有被取消，
  只是前端刷新页面/网络抖动导致 SSE 断了，Gemini Deep Research 就是这么做的（凭 `interaction_id`/
  `last_event_id` 重连接着看）。这个需求本票已经通过第 7 节的 `RunEventStore`/`EventEnvelope`
  + `Last-Event-ID`/`afterSequence` 重放覆盖，对应 `architecture.md` 已经记录的已知缺口
  （"跨刷新恢复仍然是缺口"），不需要额外设计。
