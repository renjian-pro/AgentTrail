# Runtime 与工具执行

## 目标

Runtime 负责模型驱动任务的通用执行语义，Capability Pack 只关心业务提示词、工具和产物，不复制循环、审批、持久化或审计机制。

## 一轮执行

```mermaid
sequenceDiagram
    participant App as Application Service
    participant Loop as AgentLoopExecutor
    participant Model as ChatModel
    participant Tools as ToolCallExecutor
    App->>Loop: question + RunnableParams
    Loop->>Model: system + history + files + memory
    Model-->>Loop: text/tool-call chunks
    Loop->>Tools: validated calls
    Tools-->>Loop: ordered results
    Loop->>Model: next round
    Loop-->>App: SSE events / final result
```

关键约束：

- 模型输入与服务端控制参数分离；`userId`、文件和能力开关不接受模型覆盖。
- 同一会话单飞执行；跨实例时由 Redis 锁与广播停止协调。
- 工具并发执行但结果有序回填。
- 正常完成、暂停、错误和取消都产生明确状态，不依赖日志猜测。

## 扩展点

| 扩展点 | 职责 |
|---|---|
| Tool | 可执行的原子动作和参数契约 |
| Skill | 按需加载的任务说明书 |
| Hook | 执行前后治理，如审批、预算和审计 |
| Runtime Profile | 某类会话使用的模型、工具和能力组合 |
| Persistence | 会话、暂停、记忆与 trace 的存储适配 |

## 恢复语义

普通对话的 HITL 使用 `agent_pause_state` 保存可恢复快照；恢复时重建原 Runtime Profile。PPT 使用独立的持久化状态机；DeepResearch 当前保存任务元数据和终态，阶段级 checkpoint 属于后续增强。
