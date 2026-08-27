---
status: accepted
supersedes: 0001 (决策 4、5 条)
---

# AgentTrail V1 主线改回手写 ReAct Loop；AgentScope Java 2.0 延后为有计划的 V2 引入项

## 背景

ADR 0001 决定"编排层默认交给框架跑"，采用 AgentScope Java 2.0 做自动工具执行，理由是"当时不存在需要自持循环的需求（跨请求审批挂起）"。

这个前提在两件事发生后需要重新评估：

1. **项目定位变化**：本项目的核心价值是可解释、可验证的 Runtime——"每一层如何运转、为什么这样设计"比单纯扩大功能覆盖面更重要。框架自动执行会隐藏工具循环、失败恢复和状态管理的关键边界，不利于验证这些机制。
2. **另一条平行调研的产出**：为了搞清楚"生产级 ReAct loop 到底该怎么实现"，逐行研读了一个真实企业级 Java Agent 框架的源码（Reactor Flux 驱动、流式 tool_call 分片重组、两层上下文压缩、Thinking 模式分流、单飞任务管理与中断这些机制），并照着其真实行为重新设计、独立手写实现了一遍验证原型（不是抄源码，是理解机制后自己写的一份实现）。这份验证已经直接指出 AgentTrail 自己 V0 手写 loop 里一个已知的真实缺口：工具调用的多轮对话没有正确处理 `tool_call_id`、流式分片的参数没有按 id 重组（详见 `engineering-pitfalls-and-highlights.md` #1）。

## 决策

1. **V1 主线的执行模型改回手写**：不再委托给框架的自动工具执行循环，改用手写的 `AgentLoopExecutor`（覆盖背景 2 所列的四类机制，设计已在验证原型上跑通，具体拆解见 `roadmap.md` Phase 0）。取代现有 `com.agenttrail.loop.AgentLoop`（V0 简化版）。
2. **这是排序选择，不是对 AgentScope Java 2.0 的否定**：手写版本稳定运行一段时间后，按 `roadmap.md` Phase 10 的标准重新评估是否/何时切到框架编排层。
3. **模型接入版本锁定 Spring AI 2.0 GA**（原生匹配 Spring Boot 4.1.x）。ADR 0001 第 3 条（调用方只认 `LlmClient` 接口）继续有效。手写 loop **直接对 `ChatModel` 发起流式调用，绕开 `ChatClient`/Advisor 链**——`internalToolExecutionEnabled` 开关只存在于 ChatClient 层（且 2.0 已将其移除、工具循环挪到 `ToolCallingAdvisor`），绕开这一层后自动工具执行天然不会发生，也不再受该 API 在版本间变动的影响。

## Considered Options

- **维持 ADR 0001（继续用 AgentScope Java 2.0 自动执行）**：否决。接入一个新框架、摸清它的自动执行边界情况（重试策略、错误分类、流式事件粒度）本身是一笔不小的验证成本，而且无法直接控制 Runtime 的关键语义。
- **手写 loop 永久化，彻底放弃框架路线**：否决。AgentScope Java 2.0 在 Skill/Sandbox/Session 持久化（Redis/MySQL）/Scheduler/A2A 协议上的现成覆盖是真实工程价值，长期看没有理由从零重新造这些轮子。
- **手写 loop 作为 V1 主线，框架作为有计划的 V2 演进**：采纳。

## Consequences

- ADR 0001 的第 1、2、3 条（Runtime 通用定位、V1 同步请求-响应执行模型、模型接入用 `LlmClient` 抽象）保持有效，不受影响。
- 项目叙事从"框架优先，只有验证过框架做不到才自己写"变成"先手写证明理解，再有计划地在合适的节点引入框架"——这本身是一次值得如实记录的架构决策演进，包括"为什么会先选框架、又为什么推翻"。
- 现有 `com.agenttrail.loop.AgentLoop`（含配套测试 `AgentLoopTest`/`ScriptedLlmClient`/`RecordingTool`）保留为"V0 简化版对比参考"，不删除；`AgentScopeRuntime`/`agentscope-bom` 依赖同样保留为 V2 候补，用于比较最小实现与生产级实现之间增加的机制。
- 实现工作的第一个阻塞项：验证 Spring AI 2.0 上 DeepSeek `reasoning_content` 回传行为是否已修复（1.x 有已知问题，见 `engineering-pitfalls-and-highlights.md` #5）。
