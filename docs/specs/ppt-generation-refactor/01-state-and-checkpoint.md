# 状态语义与 Checkpoint

> 上级文档：[PPT 生成能力重构需求文档](../ppt-generation-refactor-requirements.md)
> 需求范围：R1–R5

## 1. 设计原则

### 1.1 状态与生命周期分离

`pipelineState` 表示下一步要执行的业务阶段；`runStatus` 表示任务当前运行生命周期。不得依赖 `errorMsg != null` 推断任务状态。

| runStatus | 含义 |
| --- | --- |
| QUEUED | 已受理，等待 worker |
| RUNNING | worker 正在执行当前阶段 |
| WAITING_INPUT | 等待用户补充信息 |
| RETRY_WAIT | 可重试失败，等待自动或人工重试 |
| FAILED | 不可自动继续或重试预算耗尽 |
| CANCEL_REQUESTED | 已申请取消，等待安全停止 |
| CANCELLED | 已取消终态 |
| SUCCEEDED | 成功终态 |

`FAILED` 不能覆盖 checkpoint。例如失败发生在 SCHEMA 时，`pipelineState` 仍为 SCHEMA。

### 1.2 Checkpoint 提交边界

Strategy 完成全部业务动作并返回新上下文后，才允许原子提交：

- 新上下文；
- 下一 `pipelineState`；
- 新 `runStatus`；
- 递增后的 revision；
- 阶段事件。

Strategy 抛出异常时不得提前推进 `pipelineState`。查询状态接口只读取状态，不得隐式驱动执行。

## 2. R1：保留完整上下文快照

继续使用完整 JSON 快照作为当前恢复载体。快照只包含可序列化业务数据，不包含 ChatClient、线程池、连接、流式 sink 等运行时对象。

快照至少包含：

- contextVersion；
- conversationId；
- 原始需求与澄清记录；
- 结构化需求；
- 检索材料及来源引用；
- 全局视觉规划；
- templateId 和 templateVersion；
- 大纲；
- 页面 Schema；
- 素材对象引用；
- 最终 artifactId；
- warnings。

完整快照负责恢复“当前事实”，阶段事件负责记录“如何走到当前事实”，二者不能互相替代。

## 3. R2：上下文版本与迁移

- 每份快照必须包含 `contextVersion`；
- 新版本上线时必须提供旧版本迁移器；
- 无法迁移时进入明确的不可恢复失败，不得反序列化成部分 null 后继续；
- 至少保留最近两个历史版本的兼容测试；
- 迁移必须确定性、可重复，不能触发模型或外部副作用；
- 迁移失败记录源版本、目标版本与稳定错误码，但不记录敏感上下文全文。

## 4. R3：乐观锁与条件推进

任务包含单调递增的 `revision`。状态推进必须携带 expected `pipelineState` 和 expected `revision`。

更新条件不满足表示任务已由其他 worker 推进、取消或人工处理。当前 worker 必须停止提交，不能覆盖新快照。租约用于限制执行者，revision 用于保护最终提交，两者都需要存在。

建议原子更新语义：

```text
WHERE task_id = :taskId
  AND pipeline_state = :expectedState
  AND revision = :expectedRevision
  AND run_status NOT IN ('CANCEL_REQUESTED', 'CANCELLED')
```

## 5. R4：阶段事件表

每次阶段执行追加不可覆盖的事件，至少记录：

- taskId、stage、attempt；
- startedAt、finishedAt；
- outcome；
- 输入摘要与输出摘要；
- errorCode、retryClass；
- warningCode；
- workerId；
- prompt 版本；
- token 与耗时指标；
- 提交前后 revision。

事件表用于审计、排障和进度展示，不替代最新快照。摘要不得包含完整思考链、凭证、签名 URL 或无必要的用户敏感原文。

## 6. R5：外部资源耐久化

- 模板保存 templateId/templateVersion，不以本机绝对路径作为业务标识；
- 图片保存 objectKey/artifactId，不保存临时 URL；
- 最终 PPT 保存 artifactId、checksum、size、contentType；
- 签名 URL 仅在下载或渲染请求时临时生成；
- 删除或归档任务时执行明确的资源保留策略；
- 数据库不得保存图片、PPT 二进制或运行时临时目录。

## 7. 验收要点

- 任一阶段失败后都能准确识别下一安全重跑阶段；
- 两个 worker 竞争提交时只有一个 revision 更新成功；
- 旧快照能迁移后恢复，不能迁移的快照明确失败；
- 阶段事件可以还原 attempt、失败和 checkpoint 提交顺序；
- 快照中不存在本机路径、临时签名 URL 和运行时服务对象。
