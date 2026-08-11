# Ticket 16（Phase 5）：迁移普通 Chat 到 ChatApplicationService — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 15](refactor-remediation-ticket-15.md)。
> 可与 [Ticket 17](refactor-remediation-ticket-17.md)、[Ticket 18](refactor-remediation-ticket-18.md) 并行——三票各自
> 迁移互不重叠的能力包（Chat / DeepResearch / PPT），共同前提只是 Ticket 15 落地的 Run/Task/Checkpoint/Event
> 基础设施，彼此之间没有代码依赖。**唯一需要跨票协调的点**：本票新建的 `conversation.application` 统一会话
> 历史 port 是 Ticket 17（DeepResearch 完成事件处理器写历史）和 Ticket 18（PPT 完成事件处理器写历史）都要
> 复用的同一套接口/命名——三票并行开工时先在 PR 描述里同步一下 port 的方法签名，不要三份实现各写各的。

## 0. 范围边界

**这一票只做**：新建 `capability.chat.application.ChatApplicationService`，把 `AgentLoopController` 改成只
依赖它；模型选择迁到 `RuntimeProfileRegistry`；webSearch/chart 两个布尔工具开关改成 `ToolScope`；
`ConversationHistoryService`/`CapabilityConversationService` 合并挪到 `conversation.application` 统一 port；
用户/租户身份映射，梳理并移除生产路径上不该有的固定身份兜底；`/agent/v1/chat` 的 SSE 事件改成 `EventEnvelope`。
**不做**：DeepResearch/PPT 的迁移（Ticket 17/18）、`AgentLoopExecutor` 内部 6 模块拆分（Ticket 14 范围）、
`/agent/chat`（V0）本身的任何行为改动——只加日志和响应头标记。

## 1. 先验证：现状核实（写代码前必读，纠正了原始设计假设的两处）

开工前读了 `AgentLoopController.java`、`AgentLoopExecutorFactory.java`、`CapabilityConversationService.java`、
`ConversationHistoryService.java`、`PptGenerationController.java`，有两处和最初的设计假设不一致，记录下来避免
这一票和后续 Ticket 17/18 按错误前提开工：

- **固定身份字面量是 `"legacy"`，不是 `"anonymous"`**——全代码库 grep `anonymous` 只在
  `ExecuteSqlTool.java:123`（SQL 关键字黑名单，和身份无关）命中一次。真正的固定身份出现在
  `CapabilityConversationService.currentUserIdOrLegacy()`（`web/service/CapabilityConversationService.java:62-65`，
  未登录/无 HTTP 上下文时返回 `"legacy"`）、`PptTaskStore.create(String,PptGenerationContext)` 默认方法
  （`capability/ppt/PptTaskStore.java:13-15`，兜底 `"legacy"`）、`PptGenerationService.create(String,String)`
  单参重载（`capability/ppt/PptGenerationService.java:63-65`）、`PptGenerationController.create`
  （`web/controller/PptGenerationController.java:68`，`userId == null` 时显式传 `"legacy"`）。
- **`/agent/v1/chat` 本身没有 `anonymous`/`legacy` 问题**——`AgentLoopController.chat()`
  （`web/controller/AgentLoopController.java:67`）直接调 `StpUtil.getLoginIdAsString()`，Sa-Token 未登录时
  这个调用会抛 `NotLoginException`（全局默认拒绝未登录，见 `refactor-blueprint.md` §4.6），走不到"给个固定
  身份兜底"这一步。也就是说：**Chat 场景的身份问题不是"用了 anonymous"，而是"`RunnableParams` 的
  `userId`/`toolParams` 用自由 `Map<String,Object>` 携带身份和租户信息，没有强类型的 `ExecutionPrincipal`**
  （呼应 `refactor-blueprint.md` §4.6 的目标）。`"legacy"` 兜底是 `CapabilityConversationService`/PPT 那一侧
  给"能力包内部子调用/未登录场景"用的，这次统一 `conversation.application` port 时要把这条兜底逻辑一起
  梳理清楚，不能假设它已经不存在了；DeepResearch（Ticket 17）也有同样的 `null`→不记录用户归属 的分支
  （`DeepResearchController.java:67-73`），三票共用同一套身份约定时要覆盖到这个真实存在的"没有登录态"场景，
  不能简单删掉 `"legacy"` 分支了事。

## 2. `ChatApplicationService` 新建 + Controller 依赖收敛

新增 `capability.chat.application.ChatApplicationService`，对外只暴露 Chat 场景需要的用例方法（`send`/
`approve`/`stop`/`listConversations`/`history`），内部依赖 `AgentRuntimePort`（Ticket 12 冻结的契约）而不是
`AgentLoopExecutor`/`AgentLoopExecutorFactory`。

`AgentLoopController` 改造后只保留 HTTP 适配职责——参数校验、DTO 转换、调用 `ChatApplicationService`、把返回的
事件流映射成 SSE。**验收标准**：`AgentLoopController.java` 不再 `import com.agenttrail.loop.core.*`（当前
`import com.agenttrail.loop.core.AgentLoopExecutor;` 是唯一一处，见文件第 10 行）、不再 `import
com.agenttrail.loop.pause.*`（当前 `PauseState`/`PauseStateStore`/`ResumeInstruction`，第 13-15 行）、不再
`import com.agenttrail.loop.task.AgentTaskManager`（第 16 行）、不再 `import
com.agenttrail.web.service.AgentLoopExecutorFactory`（第 2 行）——这些能力全部经 `ChatApplicationService`
间接暴露成 Chat 场景语义的方法（比如 `approve(String conversationId, ApprovalDecision decision)`，不直接把
`PauseState`/`ResumeInstruction` 这些 Runtime 内部类型抛到 Controller 层）。`stop` 用例内部调用
`AgentRuntimePort.cancel(...)`，替代当前直接调 `AgentTaskManager.stopTask(...)`（`AgentLoopController.java:122`）。

**这一步依赖 Ticket 12/13 已经冻结的 `AgentRuntimePort`/`ModelGateway`/`ToolGateway` 契约**——如果这一票开工
时发现 Ticket 12/13 落地的实际接口和 `refactor-blueprint.md` §1.8 描述的签名有出入（比如方法名、参数顺序），
以已经合并的 Ticket 12/13 代码为准，不要死抠这份文档写的签名。

## 3. 模型选择迁到 `RuntimeProfileRegistry`

**现状**（先验证结论）：`AgentLoopExecutorFactory`（`web/service/AgentLoopExecutorFactory.java`，497 行）身兼
数职——12 个 telescoping 构造函数、4 个独立 `ConcurrentHashMap` executor 缓存（`plainExecutorsByModelId`/
`webSearchExecutorsByModelId`/`analyticsExecutorsByModelId`/`chartExecutorsByKey`）、一处硬编码的模型兼容性
绕过逻辑（`resolveToolCallingModel()`，第 480-488 行：`qwen-plus` 请求带工具时静默切到 `deepseek-chat`，
`deepseek-chat` 未注册直接抛 `IllegalStateException`）。这套逻辑现在散落在 `forModel`/`forModelWithCharts`/
`forAnalytics`/`forInternalOrchestration` 四个方法里，Chat 场景（`AgentLoopController`）只用到前两个。

**这一票要做的**：把"给定一个逻辑场景（Chat 普通对话/Chat 分析模式），选出实际要用的
`ChatModel`+`ThinkingMode`+`ToolCallingModel` 降级规则"这件事，从 `AgentLoopExecutorFactory` 挪到
`RuntimeProfileRegistry`（Ticket 14 已建好 `RuntimeProfile`/`RuntimeModule` 概念）——新增一个 Chat 场景的
`RuntimeProfile`（`profile=chat-default`），把 `resolveToolCallingModel()` 这条 `qwen-plus`→`deepseek-chat`
的兼容性规则登记成该 Profile 的一条装配规则，不再是方法体里裸写的字符串比较。`ChatApplicationService`
只依赖 `RuntimeProfileRegistry.resolve(profileId, modelId)` 拿到一个已经解析好兼容性问题的
`RuntimeProfile`，不自己判断"这个模型能不能带工具"。

**先验证**：Ticket 14 落地后 `RuntimeProfile`/`RuntimeModule` 的实际接口形状以 Ticket 14 合并的代码为准；
`refactor-blueprint.md` §1.8 只给了装配摘要格式示例（`profile=chat-default model=deepseek-chat
tools=[web-search,chart] pause=false memory=false trace=true persistence=jdbc`），没有给出 Java 接口签名，
不要凭空杜撰一份签名再假装是"照抄"。

**范围边界**：`AgentLoopExecutorFactory` 里 `forAnalytics`/`forInternalOrchestration` 服务的场景（数据分析、
DeepResearch/PPT 内部编排）不在这一票改造范围——`forAnalytics` 留给 SQL 能力包后续迁移票处理，
`forInternalOrchestration` 是 Ticket 17/18 的依赖点，两票各自决定要不要跟着切到 `RuntimeProfileRegistry`，
这一票不替它们做决定，只保证 Chat 场景用到的两个方法（`forModel`/`forModelWithCharts`）背后的模型选择逻辑
挪走后，`AgentLoopExecutorFactory` 剩下的方法（服务 Analytics/DeepResearch/PPT）继续能正常工作，不因为这次
改动被破坏——迁移过程中 `AgentLoopExecutorFactory` 可以先保留、内部两个方法委托给新的
`RuntimeProfileRegistry`，等 Ticket 17/18/19 都迁完之后再整体删除，不需要这一票一次性删空。

## 4. webSearch/chart 布尔开关改成 `ToolScope`

**现状**：`AgentLoopController.chat()` 用 `request.webSearchEnabled()` 一个布尔值控制要不要挂网络搜索，
`AgentLoopExecutorFactory` 对应地堆出 `forModel(modelId)`/`forModel(modelId, boolean)`/
`forModelWithCharts(modelId, boolean)`/`forAnalytics(modelId)`/`forInternalOrchestration(modelId, boolean)`
五个按参数组合命名的方法——图表工具甚至不给调用方开关（`forModelWithCharts` 的类注释原文："图表生成不像
联网搜索那样需要按对话开关…由 `AgentLoopController` 统一挂载"），布尔参数已经不够表达"这个场景默认带哪些
工具、哪些工具可选"这件事。

**这一票要做的**：新增 `ToolScope`（`capability.chat.application` 或 `runtime.tool` 包，取决于 Ticket 14
落地后 `runtime` 包的实际边界）表达"这次请求可见的工具集合"，替代布尔参数组合：

```java
public record ToolScope(boolean webSearch, boolean chart, /* 预留后续能力开关 */ Set<String> additionalToolNames) {
    public static ToolScope none() { ... }
    public static ToolScope webSearchOnly() { ... }
}
```

（具体字段形状按这一票实现时的实际需要调整，上面只是表达"结构化开关取代裸布尔参数"这个意图，不是要求
一字不差实现。）`ChatApplicationService.send(...)` 接受 `ToolScope` 而不是两个独立布尔参数，内部按
`ToolScope` 向 `RuntimeProfileRegistry`/`ToolGateway`（Ticket 13 契约）请求对应的工具列表。`AgentChatRequest`
DTO 层的 `webSearchEnabled` 字段可以保留（前端请求体不必跟着这一票改），在 Controller 层转换成
`ToolScope` 即可，不强制要求前端同步改造。

## 5. `ConversationHistoryService`/`CapabilityConversationService` 合并挪到 `conversation.application`

**现状**（先验证结论，见第 1 节）：两者都在 `web.service`——`ConversationHistoryService`
（121 行）是纯查询，直接用 `JdbcClient` 手写 SQL 查 `agent_session` 表，返回 `web.dto` 下的
`ConversationHistoryResponse`/`ConversationPageResponse`；`CapabilityConversationService`（73 行）包一层
`TurnPersistenceHook`，把 DeepResearch/PPT 这类同步能力包的结果写回 `agent_session.timeline`。两者都直接把
`web.dto` 类型作为返回值——`refactor-blueprint.md` §2.3 指出的"能力历史写入被当成 Web 层责任"就是这两个类。

**这一票要做的**：新建 `conversation.application.ConversationPort`（或按 Ticket 15 已经定的
`conversation`/`application` 分包习惯命名，以 Ticket 15 落地的实际包结构为准），合并两者职责：

```java
public interface ConversationPort {
    ConversationPage listConversations(UserId userId, int page, int size);
    ConversationHistory history(UserId userId, String conversationId, int page, int size);
    boolean belongsTo(String conversationId, UserId userId);
    Long recordCapabilityResult(CapabilityTurnRecord record); // 取代 recordSuccess/recordFailure 两个方法族
}
```

返回类型改用 `conversation.application` 自己的领域对象（不是 `web.dto.*`），HTTP 层的 mapper 负责把它们转成
现有的 `ConversationHistoryResponse`/`ConversationPageResponse` DTO——这样非 HTTP 入口（比如 Ticket 17 的
`DeepResearchTaskWorker`、Ticket 18 的 `PptWorker`）也能直接调用同一个 port，不需要经过 `web.dto` 这一层。

`recordSuccess`/`recordFailure` 现在各有"隐式当前登录用户"和"显式传 userId"两个重载（`CapabilityConversationService`
第 28-46 行），合并时把这个"身份可能没有、可能是内部子调用"的语义显式建模成 `UserId`（可空的值对象或
`Optional<UserId>`），不要继续用方法重载 + 字符串字面量 `"legacy"` 表达"没有登录态"——这是第 1 节"先验证"
指出的真实存在的分支，合并时要覆盖到，不能假装它不存在直接删掉。

**这一步不改动 `agent_session` 表结构**——`timeline` JSON 列、`question`/`answer` 文本列的落库格式不变，
这次只是把"谁来调用这套读写逻辑"的边界从 `web.service` 挪到 `conversation.application`，DB schema 层面
零改动。

## 6. 用户/租户身份映射

**范围**（按第 1 节先验证结论收窄）：Chat 场景（`/agent/v1/chat`）本身已经强制登录，这一票要做的不是
"消灭 anonymous"（这个场景压根没有），而是把 `RunnableParams` 里用 `Map<String,Object>` 承载的
`userId`/`conversation_id`（`AgentLoopController.java:68-69`）替换成 `ChatApplicationService` 用例方法的
显式强类型参数——`send(ExecutionPrincipal principal, String conversationId, String message, ToolScope
toolScope)` 这样的签名，而不是继续把身份字段塞进自由 `Map`。`ExecutionPrincipal`（或等价类型，具体命名
以 Ticket 15/`refactor-blueprint.md` §4.6 提到的强类型身份对象为准）至少要能承载 `userId`；`tenantId`
目前代码库里 `grep -rn tenantId src/main/java` 是零命中（`refactor-blueprint.md` §4.5 已确认），这一票
**不新增租户改造**（多租户在 `refactor-remediation.md` 的 Out of Scope 里明确排除），`ExecutionPrincipal`
预留 `tenantId` 字段但允许为 null，不强制这一票就要填上真实值。

DeepResearch/PPT 那一侧"没有登录态时用 `"legacy"`兜底写历史"的分支，这一票负责在合并 `conversation.application`
port 时把它保留下来并显式建模（见第 5 节），但**不负责把 DeepResearch/PPT Controller 本身的身份解析逻辑
迁移干净**——那是 Ticket 17/18 各自范围内的事，这一票只保证合并后的 port 接口能同时服务"Chat 场景总有
真实身份"和"DeepResearch/PPT 场景可能没有登录态"两种调用方，不因为这次合并让 PPT/DeepResearch 的现有行为
回退。

## 7. `/agent/v1/chat` SSE 事件改成 `EventEnvelope`；`/agent/chat`（V0）不动

**现状**：`AgentLoopController.asSse(...)`（`web/controller/AgentLoopController.java:141-145`）把
`AgentStreamEvent`（`loop/model/AgentStreamEvent.java`，sealed interface，`AgentStart`/`Text`/`Thinking`/
`ToolStart`/`ToolEnd`/`StageOutput`/`TodoProgress`/`Paused`/`Error`/`Complete` 十个变体）直接映射成
`ServerSentEvent`，`event()` 字段用 Java 类名（`event.getClass().getSimpleName()`）。

**这一票要做的**：`ChatApplicationService`/HTTP 层把这十个变体转换成 Ticket 13 定义的 `EventEnvelope`
（`eventId`/`runId`/`taskId`/`conversationId`/`sequence`/`occurredAt`/`type`/`source`/`visibility`/`payload`，
字段清单见 `refactor-blueprint.md` §1.8）。映射规则：`AgentStreamEvent` 十个变体各自对应一个
`EventEnvelope.type`（比如 `Text`→`ModelDelta`、`ToolStart`/`ToolEnd`→`ToolStarted`/`ToolCompleted`、
`Complete`→`RunCompleted`、`Paused`→`Paused`），原始变体的字段整体放进 `payload`。`sequence` 单调递增，
支持 `Last-Event-ID`/`afterSequence` 断线重放——这是 Ticket 15 的 `RunEventStore` 提供的能力，这一票只是
第一个真正把它接到 HTTP SSE 出口的消费方。

**`/agent/chat`（V0，`AgentController.java`）不做任何行为改动**——继续走 `legacy.V0.AgentRuntime`，不接
`EventEnvelope`（它本来就不是 SSE，是阻塞式 `AgentChatResponse`）。唯一要加的是可观测性标记：日志里
（或响应头，比如 `X-Runtime-Version: v0`）明确标出这是 V0 路径，方便运维/排障时区分一个请求走的是新旧
哪套引擎——不改变 `AgentController`/`legacy.V0.AgentRuntime` 的调用链本身，也不给它补超时（那是
`refactor-blueprint.md` §1.3 提到的另一个已知问题，不在这一票范围）。

## 8. Testing Decisions

- **`AgentLoopController` 现有集成测试改造后必须继续通过**——行为不变，只是内部依赖路径从
  `AgentLoopExecutorFactory`/`AgentLoopExecutor` 换成 `ChatApplicationService`/`AgentRuntimePort`。如果现有
  测试直接 mock/stub `AgentLoopExecutorFactory`，这一票要把这些测试改成对 `ChatApplicationService` 的
  依赖注入，不能删掉测试图省事。
- **新增 `ChatApplicationServiceTest`**，覆盖三个维度：
  - 模型选择：给定 `modelId=qwen-plus` + 需要工具调用的 `ToolScope`，验证 `RuntimeProfileRegistry` 解析出
    的实际模型确实被切到兼容模型（对齐现有 `resolveToolCallingModel` 的行为，不能迁移后这条兼容性规则
    悄悄失效）。
  - 工具范围：`ToolScope.webSearchOnly()`/`ToolScope.none()` 等不同取值下，验证请求到的工具列表符合预期，
    尤其验证"图表工具默认带上、不受 `ToolScope` 显式开关控制"这条现有行为（如果这一票决定把图表也做成
    可选开关，需要在票的 Further Notes 里明确记录这是行为变化，不能悄悄改变原有语义）。
  - 身份映射：验证 `ExecutionPrincipal` 正确传递到 `RunnableParams`/`AgentRuntimePort` 调用参数里，且未
    携带 `tenantId` 时不报错（因为这一票不强制要求 tenantId 有值）。
- **SSE 事件映射测试**：给定一组 `AgentStreamEvent`，验证转换出的 `EventEnvelope` 序列 `sequence` 单调
  递增、`type` 映射正确、`payload` 字段完整不丢失原始信息；`/agent/chat`（V0）响应头标记测试（验证
  `runtimeVersion=v0` 确实出现，不要求验证 V0 本身的业务行为，那部分测试已存在且不变）。
- 集成测试延续项目一贯约定，起真实 MySQL（`agent_session` 表读写路径），不用 H2 替代。

## Out of Scope

- DeepResearch/PPT 的迁移（Ticket 17/18）——包括它们各自身份兜底逻辑的彻底清理。
- 多租户改造本身（`tenantId` 只预留字段，不要求填充真实值、不要求按租户做任何隔离逻辑）。
- `AgentLoopExecutorFactory` 服务 Analytics/DeepResearch/PPT 的方法（`forAnalytics`/`forInternalOrchestration`）
  —— 这一票之后 `AgentLoopExecutorFactory` 仍然存在，只是 Chat 场景不再直接依赖它，整体删除留给后续
  Analytics/Ticket 17/Ticket 18 都迁完之后。
- `/agent/chat`（V0）本身的任何行为、超时改动——只加标记，不动逻辑。
- `AgentLoopExecutor` 内部 6 模块拆分——Ticket 14 范围，这一票假设它已经完成。
