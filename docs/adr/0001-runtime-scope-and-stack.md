---
status: partially superseded
superseded-by: 0002 (决策 4、5 条被推翻；第 1、2、3 条仍有效)
---

# AgentTrail 定位为通用 Agent Runtime；模型接入用 Spring AI，编排层默认交给 AgentScope Java 2.0

> ⚠️ 决策 4（编排层交给框架自动执行）、决策 5（默认框架选 AgentScope Java 2.0）已被 [ADR 0002](0002-hand-rolled-loop-as-v1-mainline.md) 推翻——V1 主线改为手写 ReAct Loop，AgentScope 延后为有计划的 V2 引入项。

## 背景

最初的项目设想在两个具体场景之间摇摆：迷你 Claude Code（coding agent）、固定场景的研发效能助手（代码评审/慢查询诊断）。经过一轮追问后发现两者都不对——用户真正想验收的是 **Runtime 本身**（Agent Loop、Tool Calling、记忆、状态持久化、Skill、Hooks），研究报告/PPT 生成/工作提效这些是跑在 Runtime 上的 Capability Pack，用来做验收负载，不是项目主线。

## 决策

1. **AgentTrail = 通用 Agent Runtime**，不绑定具体业务场景。Capability Pack（第一个大概率是深度研究报告）延后实现，但 Runtime 的每个机制上线时都要有一个最小负载（现有的 echo 工具）压测接口设计，防止做成空中楼阁。
2. **V1 执行模型保持同步请求-响应**，不做任务持久化/中断恢复；调用入口收成一处（Controller 只调 `AgentLoop.run()`），为未来任务化预留改动面，但现在不为它设计。
3. **模型接入层用 Spring AI**（已验证 2.0.0 GA 支持 Spring Boot 4.0.x/4.1.x，和工程当前版本一致）。调用方永远只认 `LlmClient` 接口，不直连具体厂商 SDK；未来独立的模型网关服务也是这个接口的一个新实现，调用方不用改。
4. **编排层（Agent Loop 由谁跑）默认交给框架**：用 Spring AI 的自动工具执行（不强行 `internalToolExecutionEnabled(false)` 抢控制权）。已有的手写 `AgentLoop` 分支保留在代码里，作为 V2 候补方案，不删除也不作为默认路径。
5. **默认框架具体选 AgentScope Java 2.0**（`io.agentscope`，阿里官方，2026-07-10 发布 GA，`agentscope-spring-boot-starter:2.0.0` 依赖 `spring-boot-autoconfigure:4.0.1`，与 Boot 4.1.0 同线兼容），不是 Spring AI Alibaba。

## Considered Options

- **自研完整 Runtime（含 Agent Loop）**：被否决。理由不是"自己写的不够好"，而是最初支撑"必须自持循环"的理由（V2 才需要的审批挂起）在 V1 阶段根本不成立——用当前不存在的需求去反对成熟框架，站不住脚。自研的 30 行 `AgentLoop` 保留作为 V2 候补，但不是默认路径。
- **Spring AI Alibaba（`com.alibaba.cloud.ai:spring-ai-alibaba`）**：被否决。核实到最新只有 `2.0.0-M1.1`（milestone，非 GA），版本风险偏高。起初把它和 AgentScope Java 当成同一个东西比较，是一次真实的调研失误——两者是阿里系两个独立项目，成熟度不在一个量级。
- **AgentScope Java 2.0（`io.agentscope`）**：采用。GA、阿里官方、Boot 4 兼容已验证，且生态覆盖 Skill（git/mysql/postgresql 仓库）、Sandbox（K8s/E2B/Daytona）、Session 持久化（Redis/MySQL）、Scheduler（对接 XXL-JOB）、A2A 协议，覆盖了 CONTEXT.md 里定义的大部分 Runtime 机制，不用每个都自己搭。

## Consequences

- 面试/复盘时对"为什么不自己写"的诚实答案是："V1 用框架的自动执行，只有框架验证过做不到的地方（跨请求边界的审批挂起）才会自己写"——这是被追问倒逼出的结论，不是一开始就想清楚的，值得如实讲。
- Runtime 的可扩展性验证依赖真实 Capability Pack 尽快上线，不能无限期停留在"只有 echo 工具"的状态。
- 网关（模型调用治理服务）明确排除在 AgentTrail 之外，是未来独立部署的服务；`LlmClient` 接口是两者之间唯一的耦合点。
