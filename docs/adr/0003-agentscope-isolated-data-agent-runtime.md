---
status: accepted
date: 2026-08-03
---

# AgentScope 仅作为 DataAgent 的隔离 Runtime Adapter

## 背景

DataAgent 的工程边界不应由某个 ReAct 框架定义。真正需要稳定控制的是 M-Schema/YAML 语义目录、业务消歧、SQL AST 安全、数据范围改写、脱敏、只读执行和结果校验；这些能力必须由 AgentTrail 的业务层独立负责。

AgentTrail 已有手写 `AgentLoopExecutor`、流式事件、暂停恢复、TraceAudit 和 Task/Artifact 重构路线。全局切换 AgentScope 会同时引入第二套状态、事件和工具执行语义，扩大迁移风险。

## 决策

1. 在 Phase 2A 用 AgentScope Java 2.0 做 DataAgent Pilot，入口为 `DataAnalysisAgentPort`，实现为 `AgentScopeDataAnalysisAdapter`。
2. AgentScope 只负责 DataAgent 的 ReAct/Skill Runtime、Middleware、AgentState 和工具事件；`Principal`、租户隔离、SQL 安全、数据权限重写、脱敏、查询资源限制、Artifact 和审计仍由 AgentTrail 端口与业务层负责。
3. DataAgent 使用开放式 ReAct + Skill。外层 Task/Worker/Checkpoint 只负责异步执行、恢复、取消、配额和结果持久化，不把 SQL 七阶段硬编码成不可变 DAG。
4. 生产集群使用 AgentScope 的分布式 StateStore（Redis/MySQL 等），不使用默认本地 JSON StateStore；同一 `(tenantId,userId,sessionId)` 的并发写入必须串行化。
5. 普通 Chat、DeepResearch、PPT 和 File QA 暂不迁移；Phase 10 用相同 Golden Tasks 决定是否扩大 AgentScope 的适用范围。

## 不采用的方案

- 不把 AgentScope 类型泄漏到 `capability.analytics.domain`。
- 不同时保留一条未隔离的 V0 AgentScope 路径和一条新的生产路径；V0 仅作为 demo/profile。
- 不以 Skill Prompt 作为安全边界；Bash、文件系统、SQL 等工具必须由 Runtime 工具白名单和业务策略硬隔离。

## 验收标准

- DataAgent 可以替换 AgentScope Adapter 而不改 Controller、SQL domain 和 Artifact API。
- Golden Tasks 至少覆盖 SQL 正确性、越权、敏感字段泄漏、超时/行数上限、断点恢复和跨实例同会话并发。
- AgentScope Pilot 与现有 Loop 的工具调用、事件顺序、取消、状态恢复和成本指标可对比。

