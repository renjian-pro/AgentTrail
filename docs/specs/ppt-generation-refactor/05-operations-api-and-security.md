# 操作语义、后端 API 与安全

> 上级文档：[PPT 生成能力重构需求文档](../ppt-generation-refactor-requirements.md)
> 需求范围：R26–R31

## 1. R26：CREATE

- 新建生成新 taskId；
- 相同 idempotencyKey 的网络重试返回原任务；
- 会话存在未完成任务时，由用户明确选择继续旧任务或新建，系统不静默猜测；
- 新建任务不能覆盖历史成功任务；
- 持久化任务与幂等键后立即返回统一任务视图，再由后台执行；
- 创建响应丢失时，前端可凭幂等键或用户运行任务接口找回。

## 2. R27：RESUME

- 卡片继续操作按 taskId 精确恢复；
- 文本中的“继续、重试、resume、retry”仅作为辅助入口；
- WAITING_INPUT 走补充信息接口，不走普通 resume；
- CANCELLED 默认不可继续，重新生成应创建新任务；
- SUCCEEDED 不可 resume，应提示修改或新建；
- 从当前 pipelineState 重跑该阶段，不能从 INIT 开始；
- 重复 resume 请求幂等，不得突破单任务单执行者约束。

## 3. R28：MODIFY

- 修改任务引用 baseTaskId 和 baseArtifactId；
- 使用旧 Schema 作为修改基线，不只用旧大纲重新生成整份 Schema；
- 页面具有稳定 pageId；
- 局部修改只变更目标页面及受影响素材；
- 未修改页面保持内容与布局稳定；
- 每次修改创建新任务和新 artifact，不覆盖历史版本；
- 前端展示版本关系并允许下载历史成功版本。

## 4. R29：统一任务视图

所有创建、恢复、澄清、取消、修改和查询接口返回同一种任务视图，至少包含：

- taskId、conversationId；
- operation：CREATE/MODIFY/RESUME；
- pipelineState、runStatus；
- revision；
- currentStageLabel；
- completedStages；
- progressPercent；
- clarification；
- error；
- warnings；
- artifact；
- baseTaskId；
- createdAt、updatedAt；
- canCancel、canResume、canAnswer、canDownload、canModify。

前端不得按状态字符串重复推导全部按钮权限。capability flags 由后端基于状态、归属、产物和业务规则生成。

## 5. R30：接口行为

现有路径可保持兼容，但行为必须收敛为以下契约：

| 操作 | 后端要求 |
| --- | --- |
| 创建 | 持久化任务和幂等键后立即返回 |
| 查询状态 | 纯读，不驱动执行 |
| 回答澄清 | 校验 WAITING_INPUT，原子写入补充并重新入队 |
| 继续 | 校验可恢复状态，幂等入队 |
| 取消 | 原子写 CANCEL_REQUESTED，通知执行单元 |
| 查询会话任务 | 返回该会话当前任务及历史版本 |
| 查询用户运行任务 | 支持登录后跨会话找回 |
| 下载 | 校验归属，通过 artifact 代理或签名 URL 下载 |
| 修改 | 创建引用 baseTaskId 的新任务 |

写接口接受客户端幂等键，并在冲突或非法状态时返回稳定业务错误。状态查询支持批量 taskId，以便会话历史一次刷新多张任务卡片。

## 6. R31：鉴权与归属

- 所有查询、澄清、继续、取消、修改和下载接口校验用户归属；
- conversationId 不能代替用户鉴权；
- 匿名兼容路径不得绕过任务归属；
- 签名下载 URL 短时有效且不能进入持久化上下文；
- 模板和 object key 不得直接由用户输入拼接路径；
- baseTaskId/baseArtifactId 必须属于当前用户且状态允许修改；
- 批量查询只返回当前用户有权查看的任务，并对非法请求使用一致的防枚举策略。

## 7. 前后端操作闭环

| 用户动作 | 前端 | 后端 | 最终可观察结果 |
| --- | --- | --- | --- |
| 创建 | 生成幂等键并立即建卡 | 持久化后返回 taskId | 卡片进入 QUEUED/RUNNING |
| 补充 | 绑定 taskId 提交回答 | 原子更新并入队 | 从 WAITING_INPUT 继续 |
| 重试 | 依据 canResume 展示 | 校验状态并幂等入队 | 从 failedStage 恢复 |
| 取消 | 显示取消中 | 写 CANCEL_REQUESTED 并传播 | 最终 CANCELLED |
| 下载 | 仅 artifact 可用时展示 | 校验归属并签名/代理 | 下载失败不改变任务状态 |
| 修改 | 提交 baseTaskId 与指令 | 创建版本化新任务 | 新旧 artifact 均可追溯 |

## 8. 验收要点

- 所有写操作重放后结果一致；
- WAITING_INPUT、FAILED、CANCELLED、SUCCEEDED 的非法操作被稳定拒绝；
- 前端不需要复制按钮权限状态机；
- 用户 A 不能查询、恢复、取消、修改或下载用户 B 的任务；
- 修改一页时旧任务与未变页面不被覆盖；
- 查询接口永远不触发任务推进。
