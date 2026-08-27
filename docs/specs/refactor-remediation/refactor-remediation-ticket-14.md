# Ticket 14（Phase 3）：Runtime 拆分内部模块 + telescoping constructor 清理 + Hook/StageOutputProvider 去留 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 13](refactor-remediation-ticket-13.md)。Blocks [Ticket 15](refactor-remediation-ticket-15.md)。

## 0. 范围边界

这一票只做"动 `AgentLoopExecutor`/`AgentLoopExecutorFactory` 内部结构"这一件事的三个子任务。三个子任务彼此独立、可以按顺序做也可以分给不同人并行，但都属于同一个风险类别——`refactor-blueprint.md` §1.3 强调这两个类的 telescoping constructor 目前被大量测试文件直接调用，动它们的内部结构是这次重构里风险最高的一步——所以放进同一张票，统一验证节奏和回归门禁：

① 拆分 6 模块（`AgentLoopExecutor` 收缩成 facade）
② telescoping constructor 清理（`AgentLoopExecutor` 16 个构造函数 + `AgentLoopExecutorFactory` 12 个构造函数）
③ `Hook`/`StageOutputProvider` SPI 去留决策

**不做**：不改业务层调用点——DeepResearch/PPT/Analytics 此时已经通过 Ticket 12 落地的 `AgentRuntimePort` + 委托适配器间接依赖 Runtime，本票只重排 Runtime 内部实现，`AgentRuntimePort` 对外契约不变；不改 HTTP 层，Ticket 11 建立的契约快照测试必须全程保持绿色；不新增业务功能；不引入 Maven 多模块拆分（`refactor-blueprint.md` §5 已明确这一步留到后面用 ArchUnit 先锁边界）。

**开工前必读**：Ticket 12 已经交付 `AgentRuntimePort` + 委托适配器（旧执行器通过 adapter 委托），Ticket 13 已经切出 `ModelGateway`/`ToolGateway`，`AgentLoopExecutor` 内部这时应该已经不再直接持有 Spring AI 的 `ChatModel`/`ToolCallback` 类型（那是 Ticket 13 的范围）。本票是在这个基础上继续往下拆内部执行流程，不是重新做一次类型隔离——如果开工时发现 Ticket 13 遗留的 Spring AI 类型还残留在 `AgentLoopExecutor` 字段里，先回头确认是不是 Ticket 13 验收有遗漏，不要在本票里顺手一起改，范围会失控。

---

## 1. 拆分 6 模块

按 `refactor-blueprint.md` §1.8 的目标表，把 `AgentLoopExecutor`（1206 行，[loop/core/AgentLoopExecutor.java](../../src/main/java/com/agenttrail/loop/core/AgentLoopExecutor.java)）现有方法按职责搬进 6 个新类：

| 新模块 | 只负责什么 | 当前方法来源（本次审计确认的真实行号，写票时以此为准，实际动工前应先重新核实是否因为其它并行票已经变化） |
|---|---|---|
| `ContextAssembler` | 历史、记忆、附件、系统 Prompt 组装 | `stream()` 内的消息准备逻辑 |
| `RoundDriver` | 一轮模型请求、chunk 收集、终局判断 | `scheduleRound`（649 行起）、`scheduleRoundWatchdog`（707 行起）、chunk 处理分支 |
| `ToolRoundExecutor` | 工具解析、权限校验、并发执行、结果排序 | `finishRound`（789 行起）、`executePendingToolCalls`（989 行起） |
| `RunCompletionCoordinator` | 落库、Stage、Trace、Memory、Complete 事件 | `completeRun`（1067 行起） |
| `AgentRunCoordinator` | 创建/恢复 RunContext，驱动整体生命周期 | `stream`/`resume` 外层编排逻辑 |
| `RunLifecycleManager` | 单飞、取消、暂停、租约、恢复 | `AgentTaskManager` 交互 + 暂停恢复分支（如 `ResumeInstruction.ApprovalDecision` 处理，979 行起） |

`AgentLoopExecutor` 本身收缩成只保留 `start`/`call`/`resume`/`snapshot`/`cancel` 五个 facade 方法（对齐 `AgentRuntimePort` 的五个方法——`AgentRuntimePort` 的适配器最终也是委托到这五个动作，两边形状本该一致），内部把调用转给上面 6 个模块的对应实例，facade 不再包含任何业务逻辑，只做参数转发和模块编排。

这一步**不能破坏契约快照测试**（Ticket 11 建立的）——拆分是内部重排，不是行为变更，任何可观察行为（HTTP 响应、SSE 事件顺序、错误消息文本）都不应该因为这次拆分而改变。

### 1.1 先验证：画真实的状态依赖图

**在写任何新类之前**，先读一遍现在 `scheduleRound`/`finishRound`/`AgentTaskManager` 之间的真实交互（不要凭上面的表格臆测边界，表格是目标态，不是现状的精确切割线），把下面几个问题的答案画成一张状态依赖图（Mermaid 图贴进 PR 描述即可，不需要独立文档）：

- `RoundState`/`RunContext` 里哪些字段在 `scheduleRound`/`finishRound` 之间是双向读写的（不是单向传递）？
- `AgentTaskManager` 的单飞注册/注销发生在哪几个具体调用点？是 `RoundDriver` 该直接持有这个依赖，还是应该只有 `RunLifecycleManager` 持有、`RoundDriver` 通过回调/事件通知它"这一轮结束了"？
- 看门狗定时器（`scheduleRoundWatchdog`，707-717 行，`refactor-blueprint.md` §1.4 点名的资源泄漏点，Ticket 04 的范围是把它改成 `.timeout(...)` 串联管道——如果 Ticket 04 在本票开工时还没做，本票顺手一并修掉，不要把一个已知的泄漏原样搬进新模块里继续泄漏）算 `RoundDriver` 的职责还是 `RunLifecycleManager` 的职责？
- 暂停/恢复（`PauseState`/`ResumeInstruction`）产生的 `PendingToolCall` 快照，创建者是谁、消费者是谁——这条链路横跨"正常执行流程中判定需要转审批"（偏 `RoundDriver`/`ToolRoundExecutor`）和"恢复时把审批决策塞回执行流程"（偏 `RunLifecycleManager`），是 6 个模块里耦合最深的一段，必须先画清楚再决定怎么切，不能先切了再补图。

### 1.2 迁移顺序建议

1. **`ContextAssembler` 先拆**：纯函数式的消息组装逻辑（历史/记忆/附件/系统 Prompt），输入输出明确，没有跨模块可变状态依赖，风险最低，适合作为"拆分方法论"的试点——先在这一个模块上验证"拆出去之后契约快照测试和 ArchUnit 基线仍然全绿"这套验证流程本身跑得通，再把同样的流程套用到后面几个模块。
2. `RunCompletionCoordinator` 次之——落库/Stage/Trace/Memory/Complete 事件的触发时机明确（一轮结束时），依赖的是"这一轮的最终结果"这个值对象，不持有跨轮次状态。
3. `ToolRoundExecutor` 再之后——工具解析和执行本身职责清楚，但要注意 `HIGH_RISK` 工具的审批分支和 `RunLifecycleManager` 之间有一处接口：按"`ToolRoundExecutor` 判定需要审批时返回一个明确的值对象（比如 `ToolRoundOutcome.NeedsApproval`），不直接调用 `RunLifecycleManager` 的方法"来解耦，真正触发暂停机制的动作留给上一层的 `AgentRunCoordinator` 编排。
4. `AgentRunCoordinator` 作为顶层编排者，在前三个模块都拆出来后再落地——它的职责就是"调用其它模块"，越晚拆越清楚它到底该调用谁、以什么顺序调用。
5. **`RunLifecycleManager` 和 `RoundDriver` 最后拆，且必须先完成 1.1 的状态依赖图**——这两者共享单飞注册状态、取消信号、暂停快照，是全部 6 个模块里唯一存在双向依赖的一对。如果依赖图显示两者共享的可变状态多到无法干净拆开，允许 `RoundDriver` 持有一个 `RunLifecycleManager` 的只读查询接口（比如"当前 run 是否已被取消"），但不允许反向依赖（`RunLifecycleManager` 不应该直接操作 `RoundDriver` 的内部状态）——能否保持单向依赖，是判断这一步有没有拆干净的标准。

### 1.3 契约保护

每拆完一个模块，立刻跑一次 Ticket 11 的 HTTP 契约快照测试和 ArchUnit 基线，不是三个子任务/6 个模块全部拆完才统一跑一次——每一步都跑，才能尽早发现"这次拆分改变了可观察行为"（比如事件顺序、错误消息文本）。如果快照发生变化，先判断是"实现细节重排导致的无意行为变化"（要修）还是"快照本身锁死了不该锁的实现细节"（要更新快照，但需要在 PR 里明确说明为什么这次变化是预期的，不能默默改快照了事）。

---

## 2. telescoping constructor 清理

`AgentLoopExecutor`（16 个构造函数，链式转发结构从 [AgentLoopExecutor.java:136](../../src/main/java/com/agenttrail/loop/core/AgentLoopExecutor.java) 起可以看到典型样例）和 `AgentLoopExecutorFactory`（[web/service/AgentLoopExecutorFactory.java](../../src/main/java/com/agenttrail/web/service/AgentLoopExecutorFactory.java)，497 行，24 个字段，12 个 telescoping 构造函数）都要处理。

### 2.1 先验证：枚举全部调用点

**这一步在动任何构造函数之前完成**，不能假设"生产装配只有 1-2 处、其它都是测试专用"——要实际 grep 出来分类：

```bash
grep -rn "new AgentLoopExecutor(" src/main src/test
grep -rn "new AgentLoopExecutorFactory(" src/main src/test
```

把结果分成两类：

- **生产装配**：预期只出现在装配代码（`AgentLoopExecutorConfig`/`AgentLoopExecutorFactory` 内部自引用）里，数量应该是个位数。
- **测试专用**：预期分布在各个 `*Test.java`/`*IT.java` 里，数量可能有几十处，且大概率不是均匀分布——某几个重载可能被大量测试复用，某几个重载可能只有一两处调用（这类冷门重载往往是最该先删的，删除阻力最小，适合作为迁移的起点）。

把这两类清单（文件名 + 行号 + 用的是哪个重载）贴进 PR 描述作为验证记录，不是走个过场——后续删除动作要能对照这份清单确认"删干净了、没有漏网的调用点"。

### 2.2 迁移策略：批量迁移到 Builder

`AgentLoopExecutor.Builder` 已经存在（`refactor-blueprint.md` §1.6 已确认"生产实际接了 15/18 个可选项，基本回本"），测试调用点批量迁移到它；`AgentLoopExecutorFactory` 目前没有等价的 Builder，本票新增一个 `AgentLoopExecutorFactory.Builder`，形状对齐 `AgentLoopExecutor.Builder` 的既有约定（链式 setter + 最终 `build()`），不发明一套新的构建器风格。

删除目标：**只保留"生产装配用的那一个规范构造函数"和"Builder"**，中间那些依次转发、只是为了少传几个参数而存在的构造函数全部删除。

### 2.3 半自动化迁移方法

工作量大但机械，按下面的节奏做，不要试图一次性手工改完几十处调用点：

1. 给 2.1 枚举出的、确认要删除的每一个构造函数重载标 `@Deprecated(forRemoval = true)`，附上迁移提示（`@deprecated 改用 {@link Builder}`）。
2. 跑一次全量编译（`mvn compile test-compile`），收集所有 deprecation 警告——每一条警告对应 2.1 清单里的一个调用点，警告列表本质上是清单的自动交叉验证：如果编译警告数和 2.1 手工枚举的调用点数对不上，说明 2.1 的 grep 有遗漏（比如反射构造、Spring 装配路径没被 grep 命中），要先查清楚差异再继续，不要带着遗漏往下走。
3. 逐个警告改成 Builder 写法——机械替换，一次改一个文件/一批相关的测试类，每改完一批跑一次该模块的测试，不要攒到最后一次性改完再统一验证（出错时定位范围会很大）。
4. 全部调用点清理完、警告归零后，删除标了 `@Deprecated` 的构造函数本体，只留规范构造函数 + Builder。
5. 最后再跑一次 2.1 的 grep，确认没有新的直接构造调用点漏网（比如迁移过程中不小心又写出一个新的 `new AgentLoopExecutor(...)`）。

### 2.4 用 `RuntimeProfile`/`RuntimeModule` 替代 null 参数语义

`refactor-blueprint.md` §1.8：可选能力不再通过 null 表达，而是显式模块对象。这一步和 2.2/2.3 是同一件事的两面——新的规范构造函数/Builder 不应该继续接受"传 null 表示不启用"这种语义，而是要求传一个显式的、可能是"空实现"的模块对象：

```java
public record RuntimeProfile(
        RuntimeModule.ContextCompaction contextCompaction,
        RuntimeModule.Memory memory,
        RuntimeModule.PauseResume pauseResume,
        RuntimeModule.Trace trace,
        RuntimeModule.StageOutput stageOutput,
        RuntimeModule.ToolSearch toolSearch
) {
    public static RuntimeModule.PauseResume disabled() {
        return RuntimeModule.PauseResume.DISABLED; // 显式的"关闭"值，不是 null
    }
}
```

`AgentLoopExecutor`/`AgentLoopExecutorFactory` 的规范构造函数/Builder 最终只接受两类参数：必需的核心依赖（`ModelGateway`/`ToolGateway`——Ticket 13 已交付）+ 一个 `RuntimeProfile`。

**装配失败要在启动时报错，不是第一次请求才 NPE**：新增 `RuntimeProfileValidator`（或等价校验逻辑），在 Spring 装配阶段（`@PostConstruct` 或 Bean 初始化时）校验 `RuntimeProfile` 内部依赖是否自洽（比如 `pauseResume` 启用时必须同时提供 `PauseStateStore` 依赖），校验失败直接抛异常阻止应用启动，不允许"这个模块理论上没配好，但要等到真正命中这条代码路径的第一个请求才炸出 NPE"这种情况存在。生产 Bean 装配完成后打印一行 Profile 摘要日志（`refactor-blueprint.md` §1.8 给的格式：`profile=chat-default model=deepseek-chat tools=[web-search,chart] pause=false memory=false trace=true persistence=jdbc`）——这条本来是 Phase 0（Ticket 11）的交付物，这里要确认它在新的装配路径下依然打印且信息准确，不能拆完模块之后这条日志的数据来源没跟着一起改，变成过时信息。

`RuntimeProfileValidator` 这个校验逻辑也在本票范围内，不是留给后续票。

---

## 3. Hook/StageOutputProvider SPI 去留决策

`refactor-blueprint.md` §1.6 已确认：`Hook` SPI（6 个拦截点接口）生产 0 个实现，`AgentLoopExecutorFactory` 直接硬编码 `AgentHooks.EMPTY`，连构造函数参数都不是；`StageOutputProvider`/`StageOutputManager` SPI 生产 0 个实现，工厂从未调用 `.stageOutputManager(...)`。

**这一步不是直接删代码，是先做一次决策**，产出物是一份决策记录（写进 PR 描述或 ADR，不需要独立文档），按下面的检查清单走：

### 3.1 决策：留，不删（2026-08-11 确认）

**结论**：`Hook` SPI 保留，不删除。理由分两层，都要写进最终的 PR/ADR：

**第一层——`PreToolUse` 这条已经有现成场景**：`../phase3-governance/backend-phase3-governance.md` 的
3a-1/3a-2/3a-3 已经在设计里用到了 `PreToolUseHook`（挂高危审批、限速），
`../phase3-governance/backend-phase3-governance-ticket-09.md` 第 2 节也明确写了"这里的正确做法
是……让 `PreToolUseHook` 契约在这里被拉伸使用"。如果那批治理票已经落地或排期，这条路径本身就
是"有场景，且已经在路上"，足以支撑"留"这个决定。

**第二层——横向调查 AgentScope Java（ASJ）/Spring AI Alibaba（SAA）后的补充结论**：两个框架都把
"Skill 元数据/工具注入"实现成一个真实的 Hook（ASJ 的 `SkillHook implements Hook`，挂在
`PreReasoningEvent` 上；SAA 的 `SkillsAgentHook`），证明"把技能注入做成一个可插拔的拦截点"在
生产里是被验证过的真实需求，**不是**"当初写代码顺手加的、没人真的需要的抽象"。

**但这个场景不能直接套进 AgentTrail 的 `Hook` 契约**——ASJ 的 `Hook.onEvent(T event)` 返回
`Mono<T>`，是"接收事件、可以修改后传回"的拦截器模式；AgentTrail 的 `SessionStartHook` 等六个
接口全部是 `void` 观察型（`HookContext` 也是只读 record，没有可写字段），这是 `architecture.md`
已经记录过的刻意设计："限速/预算这类需要'拦截并改变行为'的机制刻意没有塞进 Hook 契约，而是
各自成一个直接参与决策的组件"。把 Skill 工具的构建塞进 `SessionStartHook` 会违反这条已经立好
的原则（要么改契约让 Hook 能产出东西，要么 Hook 只能做旁路日志、真正干活的还是
`SkillManager`——后者和现状没有本质区别）。

**处理方式**：`SkillManager` 继续保持独立组件的身份，不做成 `Hook` 实现——这是"认同 ASJ/SAA
把技能注入当成一个独立可插拔关注点"这个思想，同时不违反 AgentTrail 自己"决策型逻辑不进 Hook
契约"的既有原则。`SkillManager` 在这个分类里和 `ToolRateLimiter`/`SessionBudgetTracker` 是
同一种角色——决策/产出型组件，不是观察型 Hook。Ticket 05 的实现方式不受这条决策影响，维持
`RunContext` 缓存字段的写法。

### 3.2 改动范围估计

- 至少完成一个真实拦截点实现，选 `PreToolUse`，对接 `../phase3-governance/backend-phase3-governance.md`
  3a/3e 描述的限速/审批场景，不需要凭空发明一个使用场景。
- 需要把这个真实实现接入生产装配——`AgentLoopExecutorFactory` 从"硬编码 `AgentHooks.EMPTY`"改成
  "按 `RuntimeProfile` 装配真实 Hook 列表"。
- 需要补齐这一条真实路径的测试（六个拦截点里只有被真实使用的那个需要测试补强，其余五个仍是
  "接口存在但暂无实现"的正常状态，不强制陪跑测试）。
- `StageOutputProvider`/`StageOutputManager` 这一条**没有找到同等分量的场景**（Phase 6/7 的
  `WorkflowEvent`/`EventEnvelope` 是否复用这套 SPI 还没定论，见 `refactor-blueprint.md` §2.8），
  按 3.1 的决定这套 SPI 也保留，但不强制在这一票补真实实现——留到 Phase 6/7 真正设计
  `WorkflowEvent` 时一并确定要不要复用它，避免在没想清楚目标形状之前先削减选项。
- 预估：改动跨 `loop/hook/`、装配代码、`loop/security/ToolRateLimiter` 接入，工作量比直接删除大，
  但换来两套 SPI 都有明确、可追溯的保留理由，不是"决定保留但继续零实现"的悬空状态。

---

## 4. Testing Decisions

- 每完成一个子任务（哪怕只是 1.2 里的一个模块拆分步骤）都跑一遍 Ticket 11 的契约快照测试和 ArchUnit 基线，不是三个子任务全部完成后才跑一次。
- 新增 Builder 覆盖度测试：确认所有原先通过老构造函数注入的机制（`ContextPolicy`/`ToolCatalog`/`PauseConfig`/`stageOutputManager`/`traceStore`/`memoryStore`/`fileStore` 等）都能通过新的规范构造函数 + `RuntimeProfile`/Builder 方式等价装配，逐项对照 2.1 枚举出的老构造函数参数列表，不能有遗漏的机制在新装配路径下"悄悄不可用了"。
- `RuntimeProfileValidator` 需要专门的失败路径测试：故意构造一个自洽性有问题的 `RuntimeProfile`（比如启用 `pauseResume` 但不提供依赖的存储），断言在 Bean 初始化阶段就抛异常，而不是等到第一次请求。
- 6 个新模块各自补单元测试，对齐 `refactor-blueprint.md` §7 已经列出的预期测试类名：`RoundDriverTest`（文本终局/单工具/多工具/工具调用分片/错误工具）、`ContextAssemblerTest`（历史/记忆/附件/输出合同注入顺序）、`ToolPolicyTest`（可见性/权限/危险级别）、`RunLifecycleTest`（单飞/取消/暂停/恢复/租约过期）、`EventContractTest`（sequence 单调递增、重放一致性——这一项如果 Ticket 15 的 `EventEnvelope` 还没落地，先写针对现有事件模型的等价测试，Ticket 15 完成后再切换）。拆分完成后新增这些测试，不能只靠原来 `AgentLoopExecutor` 的集成测试兜底覆盖率。
- 并发场景：`RunLifecycleManager` 的单飞/取消/暂停逻辑要保留原有的跨实例并发测试覆盖范围（对照现有 `AgentTaskManagerCrossInstanceIT`），拆分不能让这类测试失去覆盖。

## 5. 验收标准

沿用 `refactor-blueprint.md` §6 Phase 3：**“Runtime facade 对外接口不超过 5 个核心动作；内部实现可单独替换和测试”**——即 `AgentLoopExecutor` 收缩到 `start`/`call`/`resume`/`snapshot`/`cancel` 五个 facade 方法，6 个内部模块任一都能独立写单元测试而不需要拉起整个 `AgentLoopExecutor`。

## Out of Scope

- 业务层（DeepResearch/PPT/Analytics）的调用点改造——它们此时已经通过 `AgentRuntimePort` 间接依赖（Ticket 12 的范围），本票不涉及业务代码。
- `Hook`/`StageOutputProvider` 的具体拦截点业务实现——如果 3.3 决策为"留"，只要求接入一个最小验证实现，完整的 6 个拦截点/分阶段输出场景是 `../phase3-governance/backend-phase3-governance.md` 或后续票的范围。
- Run/Task/Checkpoint/Event 统一基础设施——这是 Ticket 15（Phase 4）的范围，本票只负责让 `AgentLoopExecutor` 内部结构准备好被 Ticket 15 的基础设施接入（比如 `RunLifecycleManager` 的租约/取消语义要能对接 Ticket 15 的 `CancellationPort`，但本票不实现 `CancellationPort` 本身）。
- Maven 多模块拆分——`refactor-blueprint.md` §5 明确"先用 ArchUnit 锁定边界更稳"，物理模块拆分不在这批票的范围内。
