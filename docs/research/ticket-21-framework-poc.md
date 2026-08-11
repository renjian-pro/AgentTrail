# Ticket 21：Agent 框架 PoC 调研报告

> 范围：仅评估框架设计可借鉴性，不把任何外部框架引入 AgentTrail 生产运行时。
> 评估基线：复用现有 `evaluation.GoldenCase`、`GoldenTaskRunner`、`GoldenTaskReport` 语义；本报告没有新增第二套评分模型。

## 结论摘要

当前最值得保留的是“端口 + 事件 + checkpoint + 能力自有状态机”的组合，而不是替换运行时。AgentScope Java 对 Harness、Middleware、State Store、SubAgent 的分层最接近本项目的 Ticket 12–20 设计，可作为后续适配层的概念参照；LangGraph 的 checkpoint/interrupt 适合校准暂停、恢复和版本化语义；AutoGen Core 可校准多 Agent 的消息来源与投递模型。CrewAI 的 Flow/Crew 分层适合指导具体的 `SupervisorWorkflow`，Dify 更像运维与编排控制面，AutoGPT 的 Block/Artifact/Schedule 适合校准能力产物和触发模型，LangChain 的结构化输出与 middleware 只作为设计参考。

本票结论：

| 框架 | 结论 | 可借鉴点 | 不采用原因 |
| --- | --- | --- | --- |
| AgentScope Java | 部分借鉴 | Harness、Middleware、State Store、SubAgent/A2A 的边界 | 当前 `AgentRuntimePort` 已能承载这些语义；引入第二套 Java Runtime 会重复状态与生命周期实现 |
| LangGraph | 设计借鉴 | checkpointer、interrupt、从 checkpoint 恢复 | Python/TypeScript 运行时，直接嵌入 Java 不合适；现有 `CheckpointStore`/`TaskCoordinator` 已覆盖主路径 |
| AutoGen Core | 设计借鉴 | 可序列化消息、direct request/response、broadcast/pub-sub、消息异常语义 | 它解决的是通用消息运行时，不是当前单 Run 能力执行器；无需替换 `RunEventStore` |
| CrewAI | 部分借鉴 | Flow 负责编排，Crew 负责角色协作 | 现有 DeepResearch/PPT 需要显式具体工作流，通用抽象会扩大迁移成本 |
| Dify | 产品/运维借鉴 | Workflow 节点、插件、触发器、流式暂停 | 面向应用编排和运营控制面，不应与 `agent_run`、能力任务表混为一谈；本票不做控制台 |
| AutoGPT | 设计借鉴 | Block 单一职责、Artifact 产物、Schedule→Run 关系 | 当前各能力已有明确 stage/strategy；统一 Block 抽象暂不能替代这些具体状态机 |
| LangChain | 设计借鉴 | Tool schema、middleware、结构化输出与校验重试 | Java 侧已有 Spring AI、`ToolGateway`、`StructuredLlmCall`；再引入 SDK 会形成两套工具调用栈 |

## 调研依据与对照点

### AgentScope Java

官方文档将 AgentScope Java 2.0 定位为生产级 Agent 平台，并分别讨论 Harness、Middleware、State Store、SubAgent 与 A2A 等构件；其状态存储强调按用户和会话维度持久化，适合对照 `AgentRuntimePort`、`EventEnvelope`、`CheckpointStore` 与 Ticket 20 的子 Agent 设计。

- [AgentScope Java 文档首页](https://java.agentscope.io/v2/en/docs/index.html)
- [Harness 与架构](https://java.agentscope.io/v2/en/docs/harness/architecture.html)
- [Context 与状态](https://java.agentscope.io/v2/en/docs/building-blocks/context.html)

判定：部分借鉴。后续如果需要框架适配，应在 `runtime.api` 外增加 adapter，不应把业务能力直接改写为 AgentScope 类型。

### LangGraph

LangGraph 的 `interrupt` 会把图状态保存到 checkpointer 后暂停，恢复时通过同一执行上下文继续；这一点可用来复核本项目的 `CheckpointStore`、`PauseResume` 和 `ResumeCommand` 是否具备明确的暂停原因与可恢复边界。

- [Breakpoints / interrupt](https://langchain-ai.github.io/langgraph/concepts/breakpoints/)
- [interrupt API](https://reference.langchain.com/python/langgraph/types/interrupt)

判定：只借鉴设计，不引入依赖。当前实现应继续以 Java 侧持久化 checkpoint 为事实来源，并为未来版本化/分支恢复预留字段。

### AutoGen Core

AutoGen Core 把消息建模为可序列化数据，并区分 direct messaging 与 broadcast/pub-sub；直接消息的异常可回传，而广播消息的处理异常不应破坏发布者。这些语义可以校准 `EventEnvelope.source`、`visibility` 与多 Agent 事件重放边界。

- [Message and communication](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/message-and-communication.html)

判定：只借鉴事件语义。`RunEventStore.afterSequence` 已提供单 Run 的顺序回放，不需要另起 Actor 消息总线。

### CrewAI

CrewAI 官方文档将 Flows 描述为带状态、监听、路由、持久化与恢复能力的编排层，将 Crews 描述为协作角色集合。该分层能帮助后续实现具体的 `SupervisorWorkflow`：Flow 负责阶段推进，Crew/Agent 负责角色任务。

- [CrewAI 官方文档](https://docs.crewai.com/)

判定：部分借鉴。当前 Ticket 20 只保留 `AgentDefinition`、`AgentRouter`、`SubAgentRunner` 和 A2A 数据结构，不引入通用 Flow/Crew DSL。

### Dify

Dify 的官方文档展示了以 Workflow/Chatflow 节点编排输入、文件、LLM、模板和输出，并提供流式工作流事件、插件和触发器能力。它更适合作为“未来是否需要独立运营控制面”的产品参照，而不是 AgentTrail 的执行内核。

- [Dify Workflow 快速开始](https://docs.dify.ai/en/guides/application-orchestrate/creating-an-application)
- [Stream workflow events](https://docs.dify.ai/api-reference/chatflows/stream-workflow-events)
- [Trigger plugin](https://docs.dify.ai/en/develop-plugin/dev-guides-and-walkthroughs/trigger-plugin)

判定：不嵌入业务数据库和生产 Runtime。若未来立项控制台，应通过 `TaskCoordinator`、事件流和能力注册表提供 API 边界。

### AutoGPT

AutoGPT 官方产品页采用可组合的 Block、Artifact、Run 和 Schedule 语义。对本项目最有价值的是单一职责 Block 和产物引用边界，可用于校准 PPT/DeepResearch 的 `ArtifactId`、媒体类型和生命周期，而不是强行统一所有能力的内部实现。

- [AutoGPT 官方产品页](https://www.agpt.co/)

判定：只借鉴 Artifact/触发关系。当前 `ArtifactId`、能力自己的 stage 和 `TaskCoordinator.submit/resume` 已足以承载第一阶段需求；定时调度另立产品票。

### LangChain

LangChain 官方文档强调结构化输出的 schema 校验、provider/tool strategy 与失败重试。它可用来复核 `StructuredLlmCall` 的“解析—校验—失败分类”边界，以及 `ToolDefinition` 是否保持稳定 schema。

- [Structured output](https://docs.langchain.com/oss/python/langchain/structured-output)

判定：只借鉴设计，不引入 SDK。Spring AI 已是 Java 侧模型入口，`ModelGateway`、`ToolGateway` 和 `StructuredLlmCall` 负责隔离具体供应商。

## Golden Task 验证计划

本票的 spec 明确允许对部分框架只做文档和源码调研；不对“不适合直接嵌入”的框架伪造运行结果。若后续对 AgentScope、CrewAI 或 AutoGPT 做 PoC，必须使用同一组 GoldenCase、同一 `GoldenTaskReport` 指标，并与当前 Runtime 直接执行结果并列比较。

建议的可复现入口：

```text
mvn -q -Dtest=GoldenTaskRunnerTest test
```

本票没有把外部框架适配器放入 `capability/*`、`runtime/*` 或 `task/*` 生产包；也没有修改业务数据库 schema。

## 未覆盖与后续工作

- 没有评估各框架当前版本的许可证组合、供应链安全和生产可观测性细节。
- 没有测量跨语言 RPC、模型调用成本或 P95 延迟；这些需要明确的实验环境和 GoldenCase 子集。
- 若要实现真正的 Supervisor 多 Agent 流程，下一步应创建具体的 `SupervisorWorkflow` 和对应的 checkpoint/event 测试，而不是引入通用 Workflow DSL。
- 若要支持定时触发，应单独设计 Schedule、Run、幂等键与重试策略。
