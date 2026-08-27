# Ticket 21（Phase 10）：框架 PoC 评估（调研票，非实现票） — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 15](refactor-remediation-ticket-15.md)。

## 0. 范围边界

**这一票只做**：用同一组 Golden Tasks（项目已有的 `evaluation.GoldenCase*` 基础设施，见第 1 节，
直接复用）分别对七个框架/平台各自要验证的具体问题，产出一份评估报告。**这一票不做**：不产出任何
框架的生产集成代码，不要求把当前 Runtime 或 Task 基础设施换成任何一个框架，不要求每个框架都必须
"用上"——调研发现不值得跟进的框架，允许在报告里直接给出"不采纳，理由是……"的结论。这一票的产出物
是 `docs/` 下的一份评估报告（文件名/位置由执行时决定，不在这张票里预先定义），不是合并进主干的
Java 代码，报告里可以包含少量一次性验证脚本/实验代码，但那些代码不进生产模块（`capability/*`、
`runtime/*`、`task/*` 等），完成后可以留在报告旁的临时目录或直接丢弃。

**2026-08-11 已定案，影响本票多处调研对照点**：本项目不引入通用 `WorkflowDefinition<S>`/
`WorkflowNode`/`WorkflowEngine` 抽象（见 `refactor-remediation-ticket-17.md` §0）。下面几节
原来拿"这个框架的机制能不能对上 `WorkflowDefinition<S>`"作为验证问题，已经改成"这个框架的机制
对 Ticket 15 的 `TaskCoordinator`/`CheckpointStore`/`RunEventStore`，或者对某个能力包自己的具体
状态机（`DeepResearchStage`/`PptState`），有没有可以参考的地方"——评估目标从"接口兼容性"改成
"具体实现的设计参考价值"，调研深度和时间预算不变。

## 1. 复用 Golden Tasks 基础设施，不新建一套评测体系

`evaluation` 包已有的基础设施直接拿来用：

- `GoldenCase`（`evaluation/GoldenCase.java`）：`id/dimension/question/asUser/referenceSql/
  expected/assertions/expectedToolCalls` 字段齐全，是这份 PoC 唯一需要的用例格式，不需要新增
  字段。
- `GoldenTaskRunner.loadAll()`（`evaluation/GoldenTaskRunner.java`）：从 `classpath*:analytics/
  golden/*.yml` 加载全部基线用例，并发执行版本 `runCaseListConcurrently(...)` 已经支持"多 case
  并发跑、结果按原始顺序落地保证报告可复现"，PoC 需要"同一组用例在不同框架下各跑一遍"时直接调用
  这个方法，不需要另写并发调度逻辑。
- `GoldenTaskReport`/`GoldenAssertion`/`ToolSelectionMetrics`：现成的通过率/失败原因/工具选择
  准确率统计，PoC 报告直接引用这些既有指标的输出，不重新定义一套评分口径。

**这一票唯一需要新增的**：给每个候选框架各写一个"适配器"，把 Golden Task 的输入（question/
asUser 等）喂给该框架跑一遍、把该框架的输出转换回 `GoldenTaskReport.GoldenObservation` 形状——
这层适配器代码是本票的实验性产出，不是生产代码，写在报告附带的临时验证目录里，验证完成后是否
保留由报告结论决定（如果某个框架结论是"不采纳"，对应适配器代码没有继续维护的价值，可以随报告一起
归档或删除，不留在仓库主干）。

## 2. 逐框架调研任务

每个框架给出"读什么、验证什么问题、多长时间给结论"，调研深度是"快速对照设计文档判断兼容性"，
不是深度重写验证：

### 2.1 AgentScope Java 2.0（1-3 天）

**读什么**：AgentScope Java 2.0 的 Harness/Middleware/State Store/SubAgent/A2A 几个核心概念的
官方文档和示例代码。

**验证什么问题**：这几个概念能不能对上 Ticket 12-15 已经建好的 `AgentRuntimePort`
（`refactor-blueprint.md` §1.8）——是"能不能长这样"的兼容性验证，不是"要不要整体换框架"。具体
核对点：AgentScope 的 `Harness` 能否一比一映射到 `AgentRuntimePort.start/call/snapshot/cancel/
resume` 这五个方法；`Middleware` 拦截点能否覆盖 Ticket 14 拆出的 `RoundDriver`/`ToolRoundExecutor`
等模块边界；`State Store` 的快照粒度能否对齐 Ticket 15 的 `CheckpointStore`（按阶段粒度还是按
整个 Run 粒度——注意本项目没有通用 Workflow 节点概念，这里的"阶段"是各能力包自己的状态枚举，
如 `DeepResearchStage`/`PptState`）；`SubAgent`/A2A 能否表达 Ticket 20 规划的 `SupervisorWorkflow`
子任务分发语义（`SupervisorWorkflow` 是具体类，不是某个通用接口的实现，见 Ticket 20 §4）。

**给结论**：用一个最小 Golden Task 子集（3-5 个 case）跑通"AgentScope Harness 包一层适配器 →
调用现有 `ModelGateway`/`ToolGateway`（Ticket 13）→ 通过率和直接走现有 Runtime 是否一致"，
结论只需要回答"接口形状兼容/需要改造才能兼容/不兼容"三选一，附具体不兼容点清单。

### 2.2 LangGraph（1-2 天，明确是读代码借鉴思路，不引入依赖）

**读什么**：LangGraph 的 `StateGraph`/`checkpoint`/`interrupt` 三个机制的设计与实现（源码，
Python/TS）。

**验证什么问题**：这三个机制的设计能不能给 Ticket 15 的 `CheckpointStore`/`TaskCoordinator`
提供改进思路。Python/TS 运行时决定了它不可能直接嵌入 Java 进程，**这条明确是"读代码借鉴思路"
不是"引入依赖"**——不产出任何 LangGraph 相关的 Java 集成代码，也不需要真的跑通任何 Golden Task
（这是这一票里唯一不要求跑 Golden Task 的框架，因为验证目标是"设计思路"而不是"能不能接入"）。

**给结论**：一份对照表——LangGraph 的 `checkpoint` 版本化策略 vs. `agent_run_checkpoint` 表的
`checkpoint_version` 设计（`refactor-remediation-ticket-15.md` 第 1 节）有什么可以借鉴的地方
（比如是否需要支持"从任意历史版本分叉重跑"）；`interrupt`（人工介入暂停点）机制 vs. 现有
`PauseStateStore`/`pause` 机制，是否有更清晰的"暂停原因分类"设计值得抄。

### 2.3 AutoGen Core（1-2 天）

**读什么**：AutoGen Core 的 Actor/Message 事件模型。

**验证什么问题**：这套事件模型对 Ticket 15 的 `EventEnvelope`（`eventId/runId/taskId/
conversationId/sequence/occurredAt/type/source/visibility/payload`）设计的参考价值——AutoGen
的消息路由（Actor 之间如何投递/订阅）是否比当前"单一 `RunEventStore` 表 + `afterSequence` 重放"
更适合未来多 Agent（Ticket 20）场景下的"一个 Run 内多个子 Agent 各自发事件"。

**给结论**：不要求跑 Golden Task（AutoGen Core 本身是一套通用消息框架，不是端到端 Agent 执行器，
接入成本和验证收益不成比例）；给结论方式和 LangGraph 一致——一份"值得借鉴/不值得借鉴"的对照点
清单，重点是事件模型是否需要为 Ticket 20 的多 Agent 场景提前调整 `EventEnvelope` 的 `source`
字段语义（比如是否需要区分"哪个子 Agent 发出的事件"）。

### 2.4 CrewAI（1-2 天）

**读什么**：CrewAI 的 Flow（流程编排）与 Crew（角色分工）两层分层设计。

**验证什么问题**：这个分层对 Ticket 20 的 `SupervisorWorkflow` 设计的参考价值
（`refactor-remediation-ticket-20.md` 第 4 节，`SupervisorWorkflow` 是一个具体类，自带
`SupervisorStage` 枚举 + 显式驱动方法，不实现任何通用接口）。具体核对点：CrewAI 的 `Flow`
（状态机式的流程编排）设计是否比当前 `SupervisorWorkflow` 草案的具体驱动方法写法更清晰，`Crew`
的角色/任务分工模型是否比当前 `SupervisorWorkflow` 草案里的 reviewer 阶段划分更完整。

**给结论**：不要求跑完整 Golden Task 集（CrewAI 面向多角色协作场景，用单轮问答类的 Golden Case
验证价值有限）；改用 2-3 个专门构造的多步骤任务（比如"调研 + 撰写 + 复核"三角色协作的场景，
可以从 DeepResearch 的批判循环场景改编，不需要新建评测体系，只是任务样本换一批）验证 Flow/Crew
分层跑下来的产物质量和 `SupervisorWorkflow` 当前设计跑同样任务是否有明显差距。

### 2.5 Dify（1-2 天，明确不嵌入业务数据库）

**读什么**：Dify 的 API/Worker/Queue/插件平台化思路。

**验证什么问题**：作为外部运营面参考——Dify 解决的是"非工程师如何编排/管理 Agent 应用"这个运营
问题，**这条要写清楚"不嵌入业务数据库"是刻意的边界**：AgentTrail 当前的 `agent_run`/
`ppt_generation_task` 等表是工程侧的执行状态存储，Dify 类平台面向的是可视化编排配置，两者数据
模型的生命周期和消费方完全不同，混在一起会让工程侧的 Task 模型承担不该承担的"运营配置"职责。
调研目标是回答"AgentTrail 未来要不要一个类似 Dify 的运营控制台"这个产品问题，不是回答"Dify 能不能
替换现有 Runtime"。

**给结论**：不跑 Golden Task（Dify 是运营面工具，不是执行引擎，没有直接对比执行准确率的意义）；
给结论方式是"是否需要规划一个独立的运营控制台产品，如果需要，Dify 的 API/Worker/Queue 分层可以
借鉴到什么程度"，这是一个产品方向判断，不是技术兼容性判断。

### 2.6 AutoGPT（1-2 天）

**读什么**：AutoGPT 的 Block/Artifact/Schedule/Run 控制模型。

**验证什么问题**：能力原子化的参考——AutoGPT 把"一个可复用的处理单元"抽象成 `Block`，产物统一
走 `Artifact`，这对 Ticket 18（PPT Artifact 化）、Ticket 17（DeepResearch Evidence/Artifact
化）已经在做的"产物走对象存储、数据库只存引用"这条路线是否有可以对齐的 `ArtifactId` 设计规范
（比如 Artifact 的类型标签、生命周期状态、版本化方式）。`Schedule`/`Run` 的关系（一次 `Schedule`
触发多次 `Run`）对当前 `TaskCoordinator.submit/resume`（Ticket 15）是否有"定时/重复触发"这个
维度上的参考价值（当前 AgentTrail 没有定时触发 Agent 任务的场景，这是判断"要不要现在就为这个
维度设计"的输入，不是必须立刻做的结论）。

**给结论**：用 3-5 个 Golden Task 验证"如果把 PPT/DeepResearch 的每个阶段方法包装成 AutoGPT 风格的
`Block`，输入输出契约是否比当前各能力包自己的具体方法签名更清晰或更繁琐"，给出"值得吸收 Artifact
命名规范/不值得引入 Block 抽象"这类具体判断，不要求全盘采纳。

### 2.7 LangChain（1 天，明确参考不引入）

**读什么**：LangChain 的 Tool Schema、Middleware、结构化输出三个机制。

**验证什么问题**：Java 侧已有 Spring AI，**这条要写清楚"参考不引入"的理由**——避免双 SDK
维护成本：AgentTrail 的 `ToolGateway`（Ticket 13）已经在 Spring AI 的 `ToolCallback` 之上做了
一层隔离，如果再引入 LangChain（即便只用它的 Java 生态或通过某种桥接），会出现两套工具调用/
结构化输出解析逻辑同时维护，`StructuredLlmCall`（Ticket 09 已经在解决"五处重复的结构化 LLM
调用逻辑"）这类统一抽象的意义也会被削弱。调研只回答"LangChain 的 Tool Schema 定义方式/
Middleware 组合方式/结构化输出重试策略里，有没有 Spring AI 当前没做好、值得抄一份设计思路（不抄
代码）的地方"。

**给结论**：不跑 Golden Task，不写任何集成代码（这是七个框架里验证成本最低、结论最明确的一个，
预期结论就是"设计思路层面小范围参考，不引入依赖"，除非调研中发现 Spring AI 有明显缺陷需要另外
立项解决）。

## 3. PoC 成功标准（沿用 `refactor-blueprint.md` §6 Phase 10）

对每个"决定要跑 Golden Task 验证"的框架（2.1/2.4/2.6，其余四个按第 2 节各自说明的理由不要求跑
Golden Task），用以下标准判断这个框架是否值得进一步跟进，不是判断"PoC 本身有没有做完"：

- 业务无需修改 `AgentRuntimePort`——适配层的改造只发生在 Runtime 内部或框架适配器里，
  `capability/*` 对 `AgentRuntimePort` 接口的调用方式不受影响。
- 事件/取消/暂停/恢复语义可映射——框架自身的生命周期钩子能一一对应到
  `EventEnvelope`/`CancellationPort`/`pause`/`resume`（Ticket 15），不需要发明新的语义层。
- 单测/集成测试/性能基线不退化——用同一组 Golden Task 跑，通过率、P95 延迟不明显劣于当前
  Runtime 直接执行的基线（`GoldenTaskReport` 现成统计口径直接复用）。
- 迁移后能删除一批自研代码，而不是增加第二套 Runtime——如果某个框架的价值只是"多一种写法"，
  没有替代掉任何现有代码，这一条不成立，对应框架应该在报告里得出"不采纳"结论。

## 4. 报告结构与产出物

报告至少包含：

1. 七个框架各自的调研结论（第 2 节每个小节的"给结论"部分汇总），每条结论标注"采纳/部分借鉴/
   不采纳"三档之一，附具体理由。
2. 对"决定要跑 Golden Task"的框架，附通过率/延迟对比表（复用 `GoldenTaskReport` 输出）。
3. 一份"如果采纳"的后续工作量粗估（不是详细设计，是"大概几个 Ticket、涉及哪些现有模块"这个
   粒度），供后续是否真正立项参考。
4. 明确列出本票范围内没有验证、留给未来的问题（比如某个框架的生产可观测性、License 合规性，
   这些不是本票 1-3 天调研深度能覆盖的）。

## 5. Testing Decisions

- 每个"要跑 Golden Task"的框架适配器代码本身需要能独立运行（不依赖 IDE 环境），至少跑通第 1 节
  提到的最小 case 子集，验证适配器本身没有写错（不是要给适配器代码建立正式的单测覆盖率要求，
  这是一次性验证脚本，不是长期维护的生产代码）。
- Golden Task 对比跑的两侧（现有 Runtime 直接执行 vs. 框架适配器执行）必须用同一批 case、同一个
  评分口径（`GoldenAssertion`/`ToolSelectionMetrics`），不能一侧宽松一侧严格导致对比失真。
- 报告结论必须附可复现的运行方式（哪怕只是一段 README 里的命令），不能只有一段文字判断——即使
  是调研票，"这个数字是怎么跑出来的"也要能让其他人重新验证一遍。

## Out of Scope

- 任何框架的生产集成代码——本票产出评估报告，不产出合并进主干的生产模块代码。
- 强制要求每个框架都必须给出"采纳"结论——调研的价值本身就包括排除不合适的选项。
- LangGraph/AutoGen Core/Dify/LangChain 四个框架的 Golden Task 对比跑（第 2 节已逐一说明理由：
  运行时不兼容、非端到端执行引擎、运营面工具、明确参考不引入）。
- 框架采纳后的详细迁移计划——报告只给"大概工作量粗估"，真正的迁移设计留给后续单独立票（如果
  报告结论支持立项的话）。
- 框架的生产可观测性、License 合规性等超出 1-3 天调研深度的深层评估，作为报告里"留给未来"的
  已知缺口列出即可。
