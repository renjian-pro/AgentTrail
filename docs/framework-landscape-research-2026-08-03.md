# Agent 框架调研：LangChain、LangGraph、Dify、AutoGPT、AgentScope、AutoGen、CrewAI

> 调研日期：2026-08-03  
> 调研范围：核心抽象、执行模型、状态与持久化、工具/MCP/A2A、并发与生产化、语言生态，以及对当前 Java/Spring 项目的迁移影响。  
> 资料策略：只采用官方文档、官方仓库或协议规范；“不支持/未发现”表示在官方材料中没有看到相应的一级能力，不代表第三方扩展不存在。

## 1. 先给结论

这七个项目并不属于同一层：

| 项目 | 更接近的产品层 | 最强的核心问题 | 是否适合直接替换当前 Java Runtime |
| --- | --- | --- | --- |
| LangChain | LLM/Agent 开发 SDK | 模型、消息、工具、结构化输出和可插拔中间件 | 否；适合 Python/TypeScript 侧车或借鉴抽象 |
| LangGraph | 有状态 Agent Workflow Runtime | 图执行、检查点、暂停/恢复、分支、循环、子图 | 否；适合把 Workflow 设计迁移为协议/概念 |
| Dify | 可视化 AI 应用平台 | 低代码编排、发布、插件、知识库和运行运维 | 否；适合作为外部平台或管理面 |
| AutoGPT | Agent 产品平台/Block Runtime | 目标驱动、可视化 Block、定时/触发、市场和运行控制 | 否；适合借鉴 Block/Artifact/运行控制 |
| AgentScope | Agent Runtime + Multi-Agent Platform | 消息/事件、ReAct、权限、状态、沙箱、子 Agent、A2A | Java 2.0 可以嵌入 Spring；值得做小范围 PoC |
| AutoGen | 事件驱动 Multi-Agent Runtime | Actor、异步消息、Teams、GraphFlow、跨进程运行时 | 否（主线 Python/.NET）；适合参考消息/Actor 模型 |
| CrewAI | Python Multi-Agent Framework + Flow Platform | Crew（自主协作）与 Flow（确定性流程）的组合 | 否；适合借鉴“业务流程包裹 Agent 团队” |

对当前 AgentTrail 的判断：不要把七个框架混成一个“Agent 基类”。更合理的是保留现有 Java/Spring 模型调用与 ReAct 能力，吸收 LangGraph/AutoGen/AgentScope/CrewAI 的可验证设计，增加自己的 `Agent`、`Workflow`、`Task`、`Tool`、`Artifact` 和策略端口。AgentScope Java 是唯一可以直接放进 JVM/Spring Boot 的候选，但也应先做隔离的能力对比 PoC，而不是全量重写。

## 2. LangChain

### 2.1 定位与核心抽象

LangChain 现在更像一个“Agent/LLM 应用 SDK”，不是完整的分布式任务系统。官方 `create_agent` 负责把模型、工具、提示和中间件组装成 Agent；模型负责决策，工具负责外部动作，中间件负责在模型调用、工具调用前后介入。LangChain 官方文档明确说 Agent loop 是“调用模型 → 模型选择工具 → 工具返回结果 → 没有更多工具调用时结束”。

- Agent：`create_agent(model, tools, middleware, ...)`。
- Tool：普通函数/协程，带名称、描述和参数 Schema；支持静态工具与运行时动态工具。
- Middleware：重试、fallback、PII 检测、限流、工具过滤、上下文压缩、人审等横切逻辑。
- Runtime：执行时提供 Context、Store、Stream Writer 和执行信息。
- Structured Output：优先使用模型原生 Schema；不支持时退回 Tool Calling Strategy。

官方参考文档把 LangChain 与 LangGraph、Deep Agents、LangSmith 分列，并把 LangChain定义为“minimal, configurable agent framework”，说明它的重点是可组合 Agent API，而不是完整托管平台。[LangChain Reference](https://reference.langchain.com/)

### 2.2 执行模型

`create_agent` 表面上是一个 Agent API，实际在 LangGraph Runtime 上运行；中间件不是第二套 Runtime，而是挂在编译后图中的 Hook。因此，一个 LangChain Agent 可以直接作为 LangGraph 的节点/子图，与确定性节点、并行分支组合。[Middleware Overview](https://docs.langchain.com/oss/python/langchain/middleware/overview)

工具的运行时选择有两类：

1. 预注册工具在每一轮按状态、权限或 Feature Flag 过滤。
2. 运行时发现工具（例如 MCP）时，需同时注册工具和处理其执行的 middleware。

这对 AgentTrail 有一个重要启示：工具发现与工具执行应是两个接口；仅把工具名塞进 Prompt 不足以形成可治理的 Tool Gateway。[Agents and Tools](https://docs.langchain.com/oss/python/langchain/agents)

### 2.3 状态与持久化

LangChain 的短期状态主要是消息和自定义 AgentState；真正的检查点/恢复由 LangGraph 提供。LangGraph 将短期 thread state 存在 Checkpointer，把跨线程长期数据存进 Store；二者可以同时启用。[LangGraph Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)

因此 LangChain Agent 的生产化关键不在 Agent 类本身，而在：

- 稳定的 `thread_id`/run id；
- durable checkpointer；
- 工具副作用的幂等；
- 可恢复的流式事件；
- 对模型和工具调用的成本、限流与审计。

### 2.4 工具、MCP 与生产能力

LangChain 提供 MCP 客户端适配器，将 MCP Server 的 Tools 转换为 LangChain Tools；MCP 的 Resource/Prompt 等能力也可通过适配器读取。[LangChain MCP](https://docs.langchain.com/oss/python/langchain/mcp)

官方 middleware 还提供模型调用次数、工具次数、fallback、PII、HITL 等控制项。[Built-in Middleware](https://docs.langchain.com/oss/python/langchain/middleware/built-in)

LangChain 本身不是任务队列、租约或跨节点调度器。其流式接口、检查点和观测需要搭配 LangGraph Runtime、LangSmith 或外部 Web/Worker 基础设施。也就是说，不能因为 `agent.stream()` 可用，就把一次 Web 请求当成可靠的长任务。

### 2.5 语言生态与 Java 迁移

官方参考文档当前列出 Python 和 JavaScript/TypeScript SDK；Go、Java 等出现在 LangSmith/生态相关列表，而不是 LangChain Agent Runtime 本身。[Reference Docs](https://reference.langchain.com/)

对 Java/Spring 的建议：

- 不做“Java 调 Python 类库”的进程内迁移；
- 通过 HTTP、gRPC、MCP 或 A2A 做 Python sidecar 时，只传结构化 Agent/Workflow 协议；
- 在 Java 侧保留自己的 `ModelGateway`、`ToolGateway` 和 `TaskCoordinator`；
- 如果只需要模型与工具抽象，Spring AI 已经覆盖相似职责，不必引入 LangChain 作为第二套核心。

## 3. LangGraph

### 3.1 核心原理：共享状态 + 节点 + 边

LangGraph 是一个低层 Workflow Runtime。官方 Graph API 将应用定义为：

- State：当前应用快照及其 Schema；
- Nodes：接收 State、执行 LLM 或普通代码、返回 State 更新的函数；
- Edges：根据状态决定下一个节点，可为固定边、条件边或动态 `Command`。

其执行算法受 Pregel 启发，使用离散 super-step：同一 super-step 的节点可以并行；节点完成后通过消息沿边激活下一批节点，所有节点停止且没有在途消息后结束。[Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)

`StateGraph` 编译时做结构检查，并注入 checkpointer、breakpoint、cache 等运行参数。State 更新使用 reducer，避免并行节点简单覆盖共享字段。`Send` 用于运行时不确定数量的 map-reduce 分支；`Command` 可以在同一节点同时更新状态和跳转。

### 3.2 执行、并发与多 Agent

LangGraph 的并发是图内并发，不等同于跨集群调度。多个出边目标会在下一个 super-step 并行执行；子图可以作为父图节点，适合 Supervisor、路由、研究分工等多 Agent 组合。[Graph API](https://docs.langchain.com/oss/python/langgraph/graph-api)

官方建议：标准 Agent loop 用 `create_agent`；需要分类路由、并行扇出、确定性步骤、循环或多个 Agent 时，把 Agent 当成 StateGraph 节点/子图。[Middleware Overview](https://docs.langchain.com/oss/python/langchain/middleware/overview)

### 3.3 持久化、暂停与恢复

编译图时配置 Checkpointer 后，LangGraph 会按步骤保存 thread checkpoint；同时可通过 Store 保存跨线程长期记忆。该机制用于对话连续性、HITL、time travel、故障恢复。[Persistence](https://docs.langchain.com/oss/python/langgraph/persistence)

`interrupt` 会将图状态保存后暂停，等待外部 `resume`；生产环境应使用 durable checkpointer。[Interrupts](https://langchain-ai.github.io/langgraph/concepts/breakpoints/)

Durable execution 不是“自动保证所有副作用只执行一次”。官方要求把 API/外部副作用放入 task，并设计幂等，以便恢复或重放时不重复产生副作用。[Functional API](https://docs.langchain.com/oss/python/langgraph/functional-api)

### 3.4 流式与可观测性

图提供 `stream/astream`，可流式输出 state updates、messages、checkpoints、tasks、debug 和 subgraph namespace；前端可以只消费消息，也可以同时订阅状态、任务和中断。[Streaming](https://docs.langchain.com/oss/python/langgraph/streaming)

这比当前 AgentTrail 的手写 SSE 更适合定义统一事件协议，但仍需在 Java 侧明确：事件序号、断线重连、租户权限和事件保留策略。

### 3.5 迁移到 AgentTrail 的价值与风险

最值得吸收的是“图状态不是 Prompt 字符串”这一设计：

```text
ResearchState {
  request;
  plan;
  evidence[];
  critique;
  artifacts[];
  nextStep;
}
```

可将现有 DeepResearch 映射为 Planner → Search Fanout → Verify → Critique → Synthesize；PPT 映射为 Requirement → Outline → Schema → Visual → Render → Review。

风险是：

- Python/TypeScript 运行时无法直接嵌入现有 Java；
- 节点重放会再次执行外部调用，必须重做幂等与 outbox；
- Graph State 版本演进、节点重命名和老线程恢复有兼容约束；
- 图内并行不自动提供模型配额、公平调度和资源隔离。

## 4. Dify

### 4.1 定位：平台，不是库

Dify 的核心是一个可视化 AI 应用平台：Workflow/Chatflow 使用画布节点组合输入、LLM、知识检索、工具、代码、条件、迭代和 Human Input。官方产品页强调 Workflow 是连接模型、检索、工具、代码、分支和人审的“harness”，可以发布为 Hosted App、API、MCP Server 或模板。[Dify Workflow Studio](https://dify.ai/workflows)

Dify 与 AgentTrail 的代码式模块不是同一层：Dify 解决“谁来配置、发布和运营一个 AI 应用”，而 AgentTrail 更关心“核心 Runtime 和能力包如何被代码扩展”。

### 4.2 核心执行模型

Workflow 是节点图，Chatflow 是带会话/流式输出的工作流。Agent 节点通过 Agent Strategy 执行多轮“模型调用 → 工具调用”，直到结束或达到 `maximum_iterations`。[Agent Strategy Plugin](https://docs.dify.ai/en/develop-plugin/dev-guides-and-walkthroughs/agent-strategy-plugin)

官方插件类型把扩展点分为：Tool、Model、Agent Strategy、Extension、Datasource、Trigger。尤其重要的是：Tool 是一个能力，Agent Strategy 是整个推理循环，二者不能混为一谈。[Choose a Plugin Type](https://docs.dify.ai/en/develop-plugin/getting-started/choose-plugin-type)

### 4.3 服务化与并发架构

Dify 官方仓库的 Docker Compose 显示其自托管部署由多个服务组成：API、Celery Worker、PostgreSQL/MySQL、Redis、Sandbox、Plugin Daemon、SSRF Proxy、前端/WebSocket 等。[Dify Docker Compose](https://github.com/langgenius/dify/blob/main/docker/docker-compose.yaml)

配置模板中明确存在 API Worker、Redis Pub/Sub、Celery Broker、任务队列、最大执行时间和最大活动请求等参数。[Dify .env.example](https://github.com/langgenius/dify/blob/main/docker/.env.example)

这说明 Dify 的并发模型是“API 接入 + 异步队列/Worker + Redis + 持久化数据库”，而不是单进程里同步跑完每个节点。代码执行、插件调用和网络访问分别有 Sandbox、Plugin Daemon、SSRF Proxy 等边界。

### 4.4 状态、SSE 和人审

Workflow API 返回 workflow run/task id；事件通过 SSE 发送。官方 API 支持在 SSE 断开后按 task id 继续订阅，并可从 persisted state snapshot 回放已执行节点；Human Input 暂停时可以选择保持连接或等外部恢复。[Stream Workflow Events](https://docs.dify.ai/api-reference/chatflows/stream-workflow-events)

这与当前项目的目标高度相关：长任务应该返回 `202 + taskId`，SSE 只是事件订阅，不应占住 Web 请求线程。

### 4.5 工具/MCP、插件与安全

Dify 支持 Dify Tool、HTTP API、MCP Tools 和受限代码执行；工作流可以直接发布为 MCP-compatible tool。[Dify Workflow Studio](https://dify.ai/workflows)

插件 SDK 将凭据、输入输出 Schema、Tool/Model/Agent Strategy 等做成可安装组件；插件还可以反向调用 Dify App、Model、Tool 和 Workflow Node。[Dify Plugin](https://docs.dify.ai/en/develop-plugin/getting-started/getting-started-dify-plugin)、[Reverse Invocation](https://docs.dify.ai/en/develop-plugin/features-and-specs/advanced-development/reverse-invocation)

生产注意事项：

- API Key 必须只存在服务端，官方 SSE 文档明确提醒不要泄漏到客户端；
- 代码节点/插件需要 Sandbox、网络出口和包安装策略；
- SSRF Proxy、Plugin Daemon、对象/文件存储和 Redis/PostgreSQL 都属于部署边界；
- 不能把低代码画布的“可运行”误认为已经具备企业租户隔离、配额、审计和灾备，仍需按部署配置验证。

### 4.6 对 Java/Spring 的迁移影响

推荐把 Dify 当成外部控制面/能力市场：

- Java 业务通过 Dify API 调用已发布 Workflow；
- 通过 MCP 把 Dify Workflow 暴露给 AgentTrail；
- Java 自己保留身份、租户、业务数据库和任务编排；
- 不把 Dify 的数据库表或 Python 节点实现直接嵌入 Spring。

适合 Dify 的场景是产品团队频繁调整 Prompt/节点、需要画布调试和运营人员配置；不适合把核心交易、权限、复杂业务状态直接交给任意用户可编辑的图。

## 5. AutoGPT

### 5.1 当前形态与历史版本区分

当前 AutoGPT 官方仓库已从早期“单个自主 Agent Demo”演进成 Build/Deploy/Run AI Agents 的平台，主线是可视化 Agent Builder、Block Workflow、运行 Dashboard、计划/触发/市场和自托管/云托管。[AutoGPT 官方仓库](https://github.com/significant-gravitas/AutoGPT)

官方站点把 AutoGPT 描述为：用户给出目标，Agent 自己制定计划并通过模型和日常应用执行每一步；支持按需、定时或 Trigger 运行。[AutoGPT 官方站点](https://www.agpt.co/)

不要把 Classic/Forge 与当前 `autogpt_platform` 当成同一个项目。仓库明确说明平台目录使用 Polyform Shield，Classic 和其它部分使用 MIT；如果做商业托管或二次平台化，必须先审许可边界。[AutoGPT License](https://github.com/significant-gravitas/AutoGPT#license)

### 5.2 核心抽象：Block 与 Workflow

AutoGPT Block 是可复用的单动作组件。官方 Block SDK 要求：

- Input Schema / Output Schema；
- `async run()` 执行并 yield 输出；
- Provider 配置和凭据字段；
- 可选 OAuth、Webhook、成本标注和测试输入输出；
- Block 由画布连接成分支、循环和数据流。

[Block SDK Guide](https://docs.agpt.co/platform/block-sdk-guide/)

这是一种“能力原子化”设计：模型决策可以选择 Block，但 Block 本身不应携带整个 Agent Runtime。AgentTrail 可以借鉴为 `ToolDefinition + InputSchema + OutputSchema + CostPolicy + CredentialPolicy`。

### 5.3 执行、状态与运营

AutoGPT 平台强调 Agent 运行的 Schedule/Trigger、Dashboard、暂停/编辑/重跑、每个 Agent/Run 的费用和人工输入提示；自托管与云托管使用同一平台代码库。[AutoGPT 官方站点](https://www.agpt.co/)、[AutoGPT README](https://github.com/significant-gravitas/AutoGPT)

官方资料没有把平台持久化协议公开成类似 LangGraph Checkpoint 或 Temporal Event History 的稳定开发接口。因此调研时应把它理解为“平台托管的运行记录和调度能力”，而不是可直接复制的通用 Durable Workflow API。

### 5.4 工具、MCP 和安全

平台以 Block/Integration 为主要扩展点，官方仓库也包含 MCP 相关实现和安全修复记录；但核心开发接口仍然以 Block SDK 为主。对本地执行、浏览器、文件和 Shell 类 Block，必须设置工作区、凭据和人工批准边界；Classic 文档明确展示了敏感文件、sudo 和工作区外命令的默认拒绝规则。[AutoGPT Classic 安全配置示例](https://github.com/significant-gravitas/AutoGPT/blob/master/classic/README.md)

### 5.5 对 Java/Spring 的迁移影响

AutoGPT 不适合直接作为 Java AgentTrail Runtime。可借鉴三点：

1. 用 Block Schema 统一描述工具输入输出；
2. 将长任务、Artifact、成本和重跑做成平台一级对象；
3. 用 Trigger/定时器启动 Workflow，而不是从 HTTP 请求里同步执行。

如果调用 AutoGPT 平台，建议通过 API/MCP 做边界，不能让业务代码依赖平台内部数据库或 Python Block 类。

## 6. AgentScope

### 6.1 定位

AgentScope 是阿里巴巴 Tongyi Lab 的 Agent Runtime/多 Agent 平台，当前同时有 Python 与 AgentScope Java。Java 2.0 官方定位为“production-ready framework for distributed, enterprise-grade agents”，可以作为 Spring Boot、Quarkus、Micronaut 或普通 JVM 应用的库。[AgentScope Java 2.0](https://java.agentscope.io/v2/en/intro.html)

这是本次调研里与当前 Java 项目最直接相关的框架。

### 6.2 核心抽象与执行模型

AgentScope 的基本 Agent 是 ReAct（reason → tool → reply）循环；AgentScope Java 2.0 在其上增加 HarnessAgent，通过 Middleware/Toolkit 增加 Workspace、Memory、Compaction、Sandbox、Sub-agent、Skills 和 Plan Mode，而不替换推理核心。[AgentScope Java 2.0](https://java.agentscope.io/v2/en/intro.html)

核心运行对象包括：

- typed Message/ContentBlock；
- Event stream（模型调用、文本增量、工具调用、工具结果、用户确认）；
- AgentState/Session；
- Model/Tool/Permission/Middleware；
- Workspace/Filesystem/Sandbox；
- Subagent、Supervisor、Handoff、Pipeline。

官方强调三态权限（allow/approve/deny）、可暂停的外部执行循环、自动上下文压缩、工具结果落盘，以及同一 `sessionId` 跨进程恢复。[AgentScope Java 2.0](https://java.agentscope.io/v2/en/intro.html)

### 6.3 状态、并发和生产化

AgentScope Java 2.0 默认可以使用本地 JsonFile State Store，也可以换成 Redis State Store，状态由 `(userId, sessionId)` 寻址；官方文档把它定位为无状态横向扩展、Kubernetes/HPA 可部署的模式。[AgentScope Java 2.0](https://java.agentscope.io/v2/en/intro.html)

Python AgentScope 的 Pipeline 提供 sequential、fanout 和 MsgHub；Fanout 默认用 `asyncio.gather()` 并发执行，也可关闭并发以控制外部服务压力。[AgentScope Pipeline](https://doc.agentscope.io/tutorial/task_pipeline.html)

这些抽象与 AgentTrail 的目标高度重合，但仍需注意：框架自带的 State Store、Scheduler 或运行服务不能自动替代业务租户隔离、配额、任务租约和 Artifact 存储。

### 6.4 MCP、Skills 与 A2A

AgentScope Java 提供 MCP、Agent Skill、Agent as Tool；工具注册可以按批量/串行/并发派发，并可通过工作区 `tools.json` 做 allowlist。[AgentScope Java 2.0](https://java.agentscope.io/v2/en/intro.html)

它还提供 A2A Client/Server：`A2aAgent` 可通过 AgentCard 或 Well-Known URL/Nacos 发现远程 Agent；Spring Boot Starter 可以直接暴露本地 Agent 为 A2A 服务，且支持 Nacos 注册。[AgentScope A2A](https://java.agentscope.io/v1/en/docs/task/a2a.html)

这使 AgentScope 成为当前项目做 A2A PoC 的最低成本选项：先把 AgentTrail 的一个能力包装成 A2A Server，再用 `A2aAgent` 调用另一个进程；内部同进程 Agent 仍使用 Java 接口，不要为了“看起来分布式”而全部走 HTTP。

### 6.5 对 AgentTrail 的迁移建议

推荐只做两个隔离实验：

1. 用 `ReActAgent`/`HarnessAgent` 重做一个轻量 Chat + 文件工具能力，比较事件、权限、上下文压缩和现有 `AgentLoopExecutor`；
2. 用 AgentScope A2A Starter 暴露一个 DeepResearch 子能力，验证 AgentCard、Task、SSE、暂停和错误映射。

如果 PoC 结果好，再考虑将现有 Runtime 内部适配到 AgentScope；不要先替换 DeepResearch/PPT，因为当前项目已经有自己的流式拼接、DeepSeek 兼容和持久化策略，直接迁移会扩大回归面。

## 7. AutoGen

### 7.1 分层结构

AutoGen 官方目前分为：

- AgentChat：易上手的单 Agent/Teams API；
- Core：基于 Actor 的事件驱动、异步消息和分布式 Agent Runtime；
- Extensions：模型、MCP、Docker Code Executor、gRPC Worker 等外部适配；
- AutoGen Studio：基于 AgentChat 的可视化原型工具。

[AutoGen 官方首页](https://microsoft.github.io/autogen/)、[AutoGen Core](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/index.html)

### 7.2 核心原理：Actor + 异步消息

Core 的 Agent 有唯一 AgentId/Metadata，通常继承 RoutedAgent，通过 `message_handler` 按消息类型处理；Runtime 负责注册 Agent、路由消息和生命周期。官方明确把 Core 定义为 Actor Model、异步消息、事件驱动、可扩展和可分布式的运行时。[Agent and Runtime](https://microsoft.github.io/autogen/dev/user-guide/core-user-guide/framework/agent-and-agent-runtime.html)

AutoGen 的消息模型允许 request/response，也允许 publish/subscribe；因此与“一个大 Service 里依次调用多个 Agent”相比，它更强调 Agent 之间的消息边界和解耦。

### 7.3 AgentChat Teams 与 GraphFlow

AgentChat 的常用 Team 包括：

- `RoundRobinGroupChat`：轮询发言；
- `SelectorGroupChat`：由模型选择下一个发言者；
- `Swarm`：用 HandoffMessage 转移控制权；
- `MagenticOneGroupChat`：面向开放任务的通用团队。

[Teams](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html)

需要严格顺序、并行、条件分支或循环时使用 `GraphFlow`，它由有向图控制 Agent 执行；官方建议简单对话用 Team，复杂业务流程再切换 GraphFlow。[GraphFlow](https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/graph-flow.html)

### 7.4 状态、暂停和分布式

AgentChat 的 Agent/Team 支持 `save_state()`/`load_state()`；状态通常是 Pydantic 可序列化结构，包含 LLM context、消息缓冲、当前轮次和终止条件。[AutoGen State](https://microsoft.github.io/autogen/stable/reference/python/autogen_agentchat.state.html)

Core 的分布式 Runtime 使用 Host + Worker：Host 维护 Worker 连接和消息投递，Worker 承载 Agent；gRPC Runtime 目前官方标注为 experimental，跨语言消息必须使用共享 protobuf Schema。[Distributed Agent Runtime](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/distributed-agent-runtime.html)

因此 AutoGen 适合研究“跨进程 Agent 消息系统”，但不应把 experimental gRPC Runtime 直接当企业生产调度器。仍需补充持久消息、重试、幂等、租约和隔离。

### 7.5 MCP 与工具

AutoGen 的 `Workbench` 把一组共享状态/资源的工具包装成统一接口；`McpWorkbench` 是 MCP Client，可通过 stdio、SSE 或 Streamable HTTP 连接 Server，支持 tools、resources、resource templates 和 prompts。[Workbench/MCP](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/components/workbench.html)、[MCP API](https://microsoft.github.io/autogen/stable/reference/python/autogen_ext.tools.mcp.html)

官方特别提醒：stdio MCP 会在本地执行命令，只连接可信 MCP Server。这与 AgentTrail 当前 Bash/文件工具的安全审计直接相关。

### 7.6 对 Java/Spring 的迁移影响

AutoGen 主线是 Python 和 .NET，多语言是通过消息协议与 Runtime 互操作，不是 Java 原生库。[AutoGen Core](https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/index.html)

推荐借鉴：

- 将 Agent 间调用改成强类型消息/事件；
- 把 Supervisor/Router/Worker 建模为 Agent，而不是 Service 私有方法；
- 为每种 Message 定义版本和序列化 Schema；
- 只有跨进程或跨团队的 Agent 才使用 A2A/消息总线。

## 8. CrewAI

### 8.1 核心定位：Crew 与 Flow 两种模式

CrewAI 是独立的 Python 多 Agent 框架，官方明确表示它不是构建在 LangChain 之上。其核心分成：

- Crew：角色化 Agent 团队，自主分工和协作；
- Flow：事件驱动、可持久化、可恢复、可审计的确定性流程；
- Task/Process：任务定义和 sequential/hierarchical 执行策略。

[CrewAI Introduction](https://docs.crewai.com/core-concepts/Agents)、[CrewAI 官方仓库](https://github.com/crewAIInc/crewAI)

官方建议：开放式研究/创作适合 Crew；决策、API 编排和审计要求高的场景适合 Flow；复杂生产应用通常用 Flow 包裹 Crew。[CrewAI Introduction](https://docs.crewai.com/core-concepts/Agents)

### 8.2 Crew 执行模型

一个 Crew 包含 Agents、Tasks、Process、可选 Manager LLM、Memory、Cache、Knowledge、Callbacks、Streaming 和 RPM 限制。Sequential Process 让任务按列表执行；Hierarchical Process 由 Manager Agent 动态分配、审查和验证任务。[Crews](https://docs.crewai.com/concepts/crews)、[Processes](https://docs.crewai.com/concepts/processes)

Crew 输出封装为 `CrewOutput`，可同时获得 raw、JSON/Pydantic 结构、每个 Task 的输出和 token usage；这比只返回一个字符串更适合能力包接口。[Crews](https://docs.crewai.com/concepts/crews)

### 8.3 Flow 执行模型与持久化

Flow 用 `@start` 标记入口，用 `@listen` 订阅前置步骤；状态可以是非结构化字典，也可以是 Pydantic Schema。Flow 支持条件、循环、路由、并行入口和事件驱动组合。[Flows](https://docs.crewai.com/concepts/flows)

`@persist` 可按类或方法保存状态；默认使用 SQLiteFlowPersistence，也允许自定义 FlowPersistence。官方支持用同一 state id 恢复，也支持从旧状态 fork 新运行。[Flows](https://docs.crewai.com/concepts/flows)

Crew 自身还提供 checkpoint：在任务完成等事件后保存状态，长任务恢复时避免重新执行已完成 Task。[Crews](https://docs.crewai.com/concepts/crews)

### 8.4 Memory、MCP 和企业部署

CrewAI 当前把短期、长期、实体和外部记忆统一为 `Memory`；保存时由 LLM 推断 scope/category/importance，召回时综合语义相似度、时效性和重要性。[Memory](https://docs.crewai.com/concepts/memory)

官方文档列出 MCP 的 stdio、SSE、Streamable HTTP、多 Server 连接和安全考虑；Crew/Flow 可把 MCP Server 暴露的工具挂到 Agent 上。[CrewAI MCP 导航](https://docs.crewai.com/concepts/flows)

CrewAI AMP 是额外的 Agent Management Platform，提供部署、REST API、实时追踪、Webhook Streaming、RBAC/团队管理和 Crew Studio，不等同于开源 Python Runtime。[CrewAI AMP](https://docs.crewai.com/enterprise/introduction)

官方材料没有显示 CrewAI 开源 Runtime 原生实现 A2A 协议；跨 Agent 的主要模型仍是同一 Python 进程中的 Crew/Flow 和工具集成。需要 A2A 时应在外围加协议适配器。

### 8.5 对 Java/Spring 的迁移影响

CrewAI 最值得迁移的不是 Python API，而是分层：

```text
Business Flow（确定性、可审计、可恢复）
  └── Crew/Agent Team（局部自主协作）
       └── Tools / Knowledge / Memory
```

这正好适合 AgentTrail：DeepResearch/PPT 作为 Flow/Workflow，Planner/Researcher/Critic/Writer 作为 Crew/Agent Team。不要把所有节点都做成自主 Agent；能用普通 Java Worker 完成的渲染、格式转换、文件上传，应该保持确定性。

## 9. 横向比较：核心机制如何对应

| 机制 | LangChain/LangGraph | Dify | AutoGPT | AgentScope | AutoGen | CrewAI |
| --- | --- | --- | --- | --- | --- | --- |
| Agent 循环 | `create_agent` + middleware | Agent Strategy Plugin | Agent/AutoPilot + Blocks | ReActAgent/HarnessAgent | AssistantAgent/AgentChat | Agent in Crew |
| 确定性流程 | StateGraph | Workflow/Chatflow Canvas | Block Graph | Pipeline/Custom Workflow | GraphFlow | Flow |
| 共享状态 | Typed State + reducers | 节点变量/运行快照 | Block 输入输出/运行记录 | AgentState/Session | Team/Agent state | Flow State/Crew context |
| 恢复 | Checkpointer + Store + interrupt | Task ID + SSE state snapshot | 平台运行控制（开发接口较少） | StateStore/session | save/load state；分布式 Runtime 实验性 | `@persist`/checkpoint |
| 并发 | super-step/fanout/Send | Worker/Celery/队列 | Block/平台调度 | fanout/async/Worker | async messages/Actor/GraphFlow | Flow listeners/Tasks |
| 工具 | Tool + middleware | Tool Plugin/MCP/HTTP/Code | Block/Integration/MCP | Toolkit/MCP/Skills | Workbench/MCP | Tools/MCP |
| 远程 Agent | 通常通过 HTTP/A2A 适配 | 发布成 MCP；A2A 需外接 | Agent Protocol/平台接口 | 原生 A2A Client/Server | 消息 Runtime；A2A 需外接 | A2A 需外接 |
| 语言 | Python、JS/TS | Python 服务 + Web 前端 | Python/TypeScript 平台 | Python、Java | Python、.NET | Python |

## 10. 对 AgentTrail 的框架选型结论

### 10.1 不建议的方案

- 不建议把 LangChain、CrewAI、AutoGen、AgentScope 同时嵌入一个核心 Runtime。
- 不建议让每个业务步骤都变成 LLM Agent，渲染、上传、Schema 校验等应保持普通 Worker。
- 不建议把 Dify/AutoGPT 的平台数据库当成自己业务的任务数据库。
- 不建议为了“多 Agent”在同一个 JVM 内部把每个方法改名为 Agent；要有身份、Schema、工具边界、事件和独立测试。
- 不建议直接使用实验性的分布式运行时作为企业调度系统，尤其是 AutoGen gRPC Runtime。

### 10.2 推荐组合

第一阶段：

```text
Spring Boot + Spring AI
  + 自有 Agent / Workflow / Task / Tool / Artifact 接口
  + Spring Modulith 做模块边界
  + PostgreSQL 任务状态与事件索引
  + Redis 队列/事件/短锁
  + 对象存储保存 PPT、图片、研究报告
```

第二阶段：

- 用 LangGraph 概念重新设计 State、Node、Edge、Checkpoint，但以 Java 接口实现；
- 用 AutoGen 概念重做 Agent 间强类型消息和事件；
- 用 CrewAI 概念把“确定性 Workflow + 局部自主 Crew”分层；
- 用 AgentScope Java 做 ReAct/Harness/A2A PoC；
- Dify 作为可选外部低代码编排与 MCP 能力市场。

第三阶段：

- 仅在长时间运行、跨节点恢复、复杂补偿明显超过自研 Task Coordinator 能力时，评估 Temporal 等 Durable Workflow；
- A2A 只用于跨进程、跨团队或跨组织 Agent，不用于同进程内部调用。

### 10.3 与现有代码的映射

| 现有概念 | 目标概念 | 借鉴来源 |
| --- | --- | --- |
| `AgentLoopExecutor` | `ReActAgentRuntime` / `Agent` | LangChain、AgentScope |
| `DeepResearchService` 私有角色方法 | Planner/Researcher/Critic/Writer Agent + Workflow | LangGraph、CrewAI |
| PPT 状态枚举 | Workflow State + Step/Worker + Artifact | LangGraph、Dify、CrewAI Flow |
| `AgentLoopExecutorFactory` | ModelGateway + AgentRegistry + ToolRegistry | LangChain Middleware/Registry |
| `AgentTaskManager` | TaskCoordinator + Checkpoint/Event Store | LangGraph、Dify、CrewAI |
| File/Bash/Code 工具 | Tool Gateway + Policy + Sandbox | AgentScope、AutoGen MCP |
| SSE | typed TaskEvent + replay/resume | LangGraph、Dify、AgentScope |
| 未来跨团队 Agent | A2A Client/Server Adapter | AgentScope A2A、A2A 规范 |

### 10.4 评估框架时应测量的指标

不要只比较“同一个 Prompt 谁回答得好”，应建立统一 Benchmark：

- 首 token 延迟、完整延迟、并发吞吐；
- 每个任务的模型调用数、Token、工具调用数和成本；
- 工具失败重试、超时、取消、幂等；
- Worker 重启后的恢复成功率；
- SSE 断线重连和事件去重；
- 任务租户隔离、越权访问、路径穿越、Shell 越权；
- 研究报告引用正确率、PPT Schema 合法率、渲染成功率；
- Workflow/Agent/Tool 版本升级后的老任务兼容性；
- Python sidecar/A2A 的网络、认证和序列化开销。

## 11. 官方来源索引

- LangChain Reference：https://reference.langchain.com/
- LangChain Agents：https://docs.langchain.com/oss/python/langchain/agents
- LangChain Middleware：https://docs.langchain.com/oss/python/langchain/middleware/overview
- LangChain MCP：https://docs.langchain.com/oss/python/langchain/mcp
- LangGraph Graph API：https://docs.langchain.com/oss/python/langgraph/graph-api
- LangGraph Persistence：https://docs.langchain.com/oss/python/langgraph/persistence
- LangGraph Durable Execution：https://docs.langchain.com/oss/python/langgraph/functional-api
- LangGraph Streaming：https://docs.langchain.com/oss/python/langgraph/streaming
- Dify Workflow Studio：https://dify.ai/workflows
- Dify Plugin Types：https://docs.dify.ai/en/develop-plugin/getting-started/choose-plugin-type
- Dify Agent Strategy：https://docs.dify.ai/en/develop-plugin/dev-guides-and-walkthroughs/agent-strategy-plugin
- Dify SSE Resume：https://docs.dify.ai/api-reference/chatflows/stream-workflow-events
- Dify Docker Compose：https://github.com/langgenius/dify/blob/main/docker/docker-compose.yaml
- Dify Environment：https://github.com/langgenius/dify/blob/main/docker/.env.example
- AutoGPT 官方站点：https://www.agpt.co/
- AutoGPT 官方仓库：https://github.com/significant-gravitas/AutoGPT
- AutoGPT Block SDK：https://docs.agpt.co/platform/block-sdk-guide/
- AgentScope Python 文档：https://doc.agentscope.io/
- AgentScope Pipeline：https://doc.agentscope.io/tutorial/task_pipeline.html
- AgentScope Java 2.0：https://java.agentscope.io/v2/en/intro.html
- AgentScope Java A2A：https://java.agentscope.io/v1/en/docs/task/a2a.html
- AutoGen 官方首页：https://microsoft.github.io/autogen/
- AutoGen Core：https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/index.html
- AutoGen Teams：https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/tutorial/teams.html
- AutoGen GraphFlow：https://microsoft.github.io/autogen/stable/user-guide/agentchat-user-guide/graph-flow.html
- AutoGen Distributed Runtime：https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/framework/distributed-agent-runtime.html
- AutoGen MCP Workbench：https://microsoft.github.io/autogen/stable/user-guide/core-user-guide/components/workbench.html
- CrewAI Introduction：https://docs.crewai.com/core-concepts/Agents
- CrewAI Crews：https://docs.crewai.com/concepts/crews
- CrewAI Processes：https://docs.crewai.com/concepts/processes
- CrewAI Flows：https://docs.crewai.com/concepts/flows
- CrewAI Memory：https://docs.crewai.com/concepts/memory
- CrewAI AMP：https://docs.crewai.com/enterprise/introduction
- A2A Protocol：https://github.com/a2aproject/A2A/blob/main/docs/specification.md
- MCP Specification：https://modelcontextprotocol.io/specification/2025-06-18/server/index
