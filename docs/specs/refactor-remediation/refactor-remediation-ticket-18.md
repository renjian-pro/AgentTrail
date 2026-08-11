# Ticket 18（Phase 7）：PPT 改造为异步 Worker+Artifact — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 15](refactor-remediation-ticket-15.md)。
> 可与 [Ticket 16](refactor-remediation-ticket-16.md)、[Ticket 17](refactor-remediation-ticket-17.md) 并行。Blocks
> [Ticket 20](refactor-remediation-ticket-20.md)。

## 0. 范围边界

**这一票只做**：落地 `refactor-blueprint.md` §2.8"PPT 并发与资源安全改造（硬性规则）"清单里的
八条——渲染动作挪到 `RenderPort` 后面、每个任务先拿数据库租约再执行当前节点、`RENDER` 独立线程池
+ 全局并发上限、每任务独立临时目录+清理、路径 allowlist、产物存 MinIO/S3+签名 URL、创建任务支持
`Idempotency-Key`、失败节点按 `RetryClass` 分类。**不做**：重新设计 `PptState` 状态机本身（9 个值
+ `strategy/*Strategy.java` 保留原样，见第 1 节"先验证"的结论）、DeepResearch/Chat 的迁移
（Ticket 16/17）、模板系统改造成"按需求选模板"（`TemplateStrategy` 类注释已经写明这不在当前范围）、
真正接入多套渲染引擎。

**这一票和 Ticket 17 的关键差异**：DeepResearch 是"纯内存 → 需要从零建 Workflow+Checkpoint"，PPT
是"已经有 DB checkpoint 的状态机 → 缺的是并发/资源安全这一层"。不要把这一票做成"重写 PPT 状态机"，
`PptGenerationService.run(long)` 按 `PptState` 顺序推进、`taskStore.advance(...)` 只在
`strategy.execute` 真正返回后才落库这套"避免 checkpoint 顺序 bug"的设计（`PptGenerationService`
类注释）本身没有问题，继续复用。

## 1. 先验证：现有实现现状核实

开工前完整读了 `capability/ppt/PptGenerationService.java`（222 行）、
`capability/ppt/strategy/RenderStrategy.java`、`capability/ppt/PptPythonRenderer.java`、
`capability/ppt/JdbcPptTaskStore.java`、`capability/ppt/image/MinioPptImageStore.java`、
`web/controller/PptGenerationController.java`、`web/config/PptGenerationConfig.java`，把票面
描述的每一条"现状问题"对照真实代码核实一遍，不是照抄 `refactor-blueprint.md` §2.3/§2.8 的结论
就假设都对：

- **① Controller 不直接运行 Python——核实结论：Controller 本身没有直接持有
  `PptPythonRenderer`，但 `RenderStrategy` 直接持有并同步调用它**：`RenderStrategy`
  （`capability/ppt/strategy/RenderStrategy.java:37,71`）构造函数直接接收 `PptPythonRenderer
  renderer` 字段，`execute(...)` 里 `renderer.render(...)` 是一次同步阻塞调用
  （`PptPythonRenderer.render` 内部 `process.waitFor(timeoutSeconds, TimeUnit.SECONDS)`）。
  `PptGenerationController` 的 `create`/`resume` 确实已经把 `pptGenerationService.run(taskId)`
  丢给后台线程池执行（`PptGenerationController.java:114-134`），**但真正的问题是这个后台线程池
  是全状态机共用的一个 4 线程池（`pptGenerationExecutor`，`PptGenerationConfig.java:59-68`
  `Executors.newFixedThreadPool(4, ...)`），INIT/REQUIREMENT/.../RENDER 全部 9 个状态、含 RENDER
  这个唯一真正吃 CPU/磁盘/子进程的阶段在内，全部挤在同一个线程池配额里**——这是这一票要解决的
  真实问题，不是"Controller 跑 Python"这个更粗糙的描述，第 3 节据此设计。
- **② `PptTaskStore` 确实没有分布式租约/幂等键，且这不是理论风险**：`JdbcPptTaskStore`
  类注释明确写着"这一票的编排是单线程顺序跑……不存在同一个任务被并发推进的场景"
  （`JdbcPptTaskStore.java:16-18`）——这个假设在"单实例 + 只有 `/create`/`/resume` 两个触发点"
  下成立，但 `PptGenerationController.resume(taskId)`（`PptGenerationController.java:74-83`）
  没有检查这个 `taskId` 是否已有一个 `run(taskId)` 在执行中就直接又 `pptGenerationExecutor.
  execute(...)` 一次——用户对同一个卡住的任务连点两次"继续"，或多实例下两个实例同时收到对同一
  `taskId` 的 `/resume` 请求，`advance(...)` 各自读到同一份旧状态、各自往前推一步、后写覆盖先写，
  这是当前代码就能触发的竞态，不是假设性风险。`advance`/`markFailed` 单行 UPDATE 天然原子，但
  防不住"两个线程各自基于同一份旧状态计算出下一步"这种应用层竞态。
- **③ `RENDER` 用独立线程池——核实"是否已有线程池调参"**：`pptGenerationExecutor` 是
  `newFixedThreadPool(4, ...)`，硬编码不可配置，和 `refactor-blueprint.md` §1.5 指出的问题一致，
  但**这一票不改这个数值本身**（那是 Ticket 03 的范围）——先验证 Ticket 03 是否已完成：已完成则
  在其可配置+有界队列的基础上把 RENDER 拆成第二个独立池；未完成则这一票顺带把两件事一起做掉
  （拆分优先级更高，不因为 Ticket 03 未排期就让 RENDER 继续和纯 LLM 阶段共享配额）。
- **④ 独立临时目录——核实现状**：`RenderStrategy.execute(...)`（`RenderStrategy.java:60-62`）
  用 `conversationId + "-" + UUID` 拼文件名，但写入的是**同一个共享 `outputDir`**
  （`agenttrail.ppt.output-dir` 单一配置项），靠 UUID 避免碰撞，不是每个任务一个独立目录——
  没有"这个任务的产物全部在一个目录下，好清理"这个边界，磁盘配额和保留策略无从谈起。
- **⑤ 路径 allowlist——核实"用户输入能不能直接拼进文件路径"**：核实结论是**当前不能，但不是
  因为有 allowlist 机制，是因为当前唯一的模板来源是配置写死的单一文件**：`TemplateStrategy`
  类注释自己写明"这一票只有一份固定模板……'按需求选不同模板'不在这一票范围内"，`templatePath`
  来自配置项，不接受任何请求参数；`RenderStrategy` 的输出路径由 `outputDir.resolve(fileId +
  "...")` 拼接，`fileId` 里的 `conversationId` 是自由文本、**没有做路径穿越字符过滤**就参与
  文件名拼接（`resolve` 对 `..` 片段仍按路径分量处理，需要 `normalize()` + 前缀校验才能确认是否
  真能跳出目录）——这是需要实际验证、不能假设"应该没问题"的检查点。**结论**：模板路径本身当前
  没有注入风险，但 `conversationId` 参与拼接产物路径这条链路缺一次显式的规范化校验，这一票要
  补上，不能依赖"以后不会有按需求选模板这个功能"这个假设。
- **⑥ MinIO bucket 公开读——核实结论：确实是公开读，且是代码显式设置，不是配置疏漏**：
  `MinioPptImageStore.ensureBucketReady()`（`MinioPptImageStore.java:69-91`）首次使用时惰性建
  bucket 后立即调用 `setBucketPolicy(...)` 设置 `Principal: ["*"]` 的公开读策略
  （`publicReadPolicy`，`MinioPptImageStore.java:93-107`），类注释解释这是当时为免运维手工建
  bucket 做的取舍，**这一票要把它改掉**，改成私有 bucket + 签名 URL（或授权代理端点）。数据库
  当前存的是完整公网 URL（`schema.coverImageUrl()` 直接落进 `context_json`），改成私有 bucket
  后要改成只存 object key（对应 §2.8"数据库只存 ArtifactId"），URL 下发给前端时才按需签名。
- **⑦ 幂等键——核实结论：当前完全没有**：`grep -rn "idempoten"` 命中零处（`PptGenerationRequest`
  只有 `conversationId`/`message` 两个字段），`create` 每次调用都新建一行任务，客户端网络重试
  会产生两条独立任务、各自跑一遍完整 LLM+文生图+渲染流程，是真实的重复计费风险。
- **⑧ 下载接口权限校验——核实结论：认证用户有校验，匿名请求没有**：`download(...)`
  （`PptGenerationController.java:150-166`）经 `PptTaskStore.findById(userId, taskId)` 按
  `task.userId()` 过滤，**但前提是 `currentUserId()` 返回非 null**——未登录/无会话上下文时
  `currentUserId()` 返回 `null`，此时 `download`/`status`/`cancel` 全部落到"userId == null"
  分支，直接调用不带用户过滤的 `describe(taskId)`/`outputFileOf(null, taskId)`，**任何未登录
  请求可以下载任意 `taskId` 的产物，只要猜得到自增主键**——这是一个真实的越权下载漏洞，第 9 节
  据此设计测试。

## 2. `RenderPort` 抽象 + `PptWorker`

新建 `capability.ppt.application` 包（沿用 `refactor-blueprint.md` §2.8 迁移映射表的目标位置
`capability.ppt.application.PptWorkflow`——这是一个具体类名，不实现任何通用接口）。

**2026-08-11 已定案：`PptGenerationService` 现有的 `PptState` 枚举 + `Map<PptState,
PptGenerationStrategy>` 状态机骨架不做任何形式上的迁移，原样保留**——本项目已经决定不引入通用
`WorkflowDefinition<S>`/`WorkflowNode` 抽象（见 `refactor-blueprint.md` §2.8、Ticket 17 §0），
PPT 这套状态机反而是 DeepResearch（Ticket 17）在照抄的对象，不存在"PPT 要不要也迁移成 Workflow
接口"这个问题——**这一票只做** `RenderPort`/`PptWorker` 两个具体件，把渲染动作和并发/资源安全
规则接进去，不改状态机本身的实现方式。

```java
package com.agenttrail.capability.ppt.application;

/** 渲染动作的端口——把 RenderStrategy 对 PptPythonRenderer 的直接依赖切断，
  * 使渲染动作可以被 PptWorker 单独调度到独立资源池，也为将来替换渲染引擎（比如换成
  * LibreOffice headless 或云端渲染服务）留出实现切换点，不需要改 RenderStrategy 本身。 */
public interface RenderPort {
    /** @return 渲染产物的 ArtifactId（已经写入独立存储，不是本地文件路径） */
    ArtifactId render(RenderRequest request);
}

public record RenderRequest(String taskId, String templatePath, PptSchema schema, Path workDir) {
}
```

`RenderStrategy` 改成依赖 `RenderPort` 而不是直接依赖 `PptPythonRenderer`；`PptPythonRenderer`
下沉一层，变成 `RenderPort` 的一个具体实现（`ProcessBuilderRenderPort` 或类似命名），
`ProcessBuilder`/子进程管理这些细节全部留在这个实现类内部，`RenderStrategy` 之后只知道
"调用一个渲染端口拿到 ArtifactId"，符合 `refactor-blueprint.md` §2.8 迁移映射表"业务禁止依赖：
`ProcessBuilder`"这条规则。

`PptWorker`（新建，`capability.ppt.application.PptWorker`）职责：消费 Ticket 15 的
`TaskQueue`/`TaskCoordinator`，对每个待推进的任务，先按第 3 节拿数据库租约，租约成功后调用
`PptGenerationService`（或迁移后的 `PptWorkflow`）推进当前节点；`RENDER` 节点的实际渲染动作
通过第 4 节的独立线程池提交，不占用 `PptWorker` 自身的调度线程。`PptGenerationController`
收缩成：`create`/`resume` 只做校验 + 通过 `TaskCoordinator.submit(...)`/`resume(...)` 把任务
交给 `PptWorker` 消费的队列，立即返回 `{runId, taskId}`；`status`/`cancel`/`download` 保持现有
形状（查询/取消/下载不需要驱动执行，这三个端点的现有实现已经是"纯读/纯状态位"，不需要大改，
只是查询源从 `PptTaskStore` 直查改成通过 Ticket 15 的 `RunRepository` 或 `PptTaskStore` 与
`agent_run` 表并存——具体是否要求 PPT 也复用 `agent_run` 这张统一表，还是继续用自己的
`ppt_generation_task` 表只是把租约/幂等这两列加上去，取决于 Ticket 15 是否强制业务包统一落
`agent_run`；**先验证**这一点，如果 Ticket 15 没有强制统一存储，这一票选择成本更低的路径：
继续用现有 `ppt_generation_task` 表，只新增 `lease_owner`/`lease_until`/`idempotency_key` 三列，
不强行搬到 `agent_run`）。

## 3. 数据库租约：复用 Ticket 15 的 `LeaseManager`

按第 1 节②确认的真实竞态场景（`/resume` 重复触发、多实例并发推进同一任务）设计：`PptWorker`
在执行任意一个节点之前，先用 Ticket 15 的 `LeaseManager.tryAcquire(resourceId, ttl)`
（`resourceId` 用 `"ppt-task-" + taskId`）申请租约，拿不到直接跳过这次调度（说明另一个 worker
正在处理这个任务），拿到之后才调 `strategy.execute(context)`，节点执行完（无论成功失败）
`LeaseManager.release(resourceId)`。渲染这一步耗时可能超过普通节点，租约 TTL 需要覆盖
`agenttrail.ppt.render-timeout-seconds`（当前默认 60s，`PptGenerationConfig.java:75`）之上留出
余量，且 `LeaseManager` 的 `renew` 能力（`refactor-remediation-ticket-15.md` 第 2.1 节）在
RENDER 节点执行期间要被调用（比如渲染子进程运行期间每隔一段时间续租一次），避免"渲染确实还在跑
但租约到期、另一个 worker 认为可以接管"这种误判。

`PptGenerationController.resume(...)` 改造后不再直接 `pptGenerationExecutor.execute(...)`，而是
把"继续跑这个任务"变成一次入队请求（`TaskCoordinator.resume(taskId)`），真正的执行由持有租约的
`PptWorker` 完成——这样重复点击"继续"最多是重复入队，不会重复执行。

## 4. `RENDER` 独立线程池 + 全局并发上限

**先验证**：确认 Ticket 03（"HikariCP 连接池 + 后台线程池调参"）是否已交付，若已交付，
`pptGenerationExecutor` 应该已经是可配置大小 + 有界队列；这一票在此基础上新增第二个专用池
`pptRenderExecutor`（同样可配置大小 + 有界队列 + 拒绝策略，参数命名对齐
`agenttrail.ppt.render.*` 前缀，和其余 `agenttrail.ppt.*` 阶段共用的 `pptGenerationExecutor`
区分开）。若 Ticket 03 未交付，这一票的 `pptRenderExecutor` 直接按可配置+有界的标准实现，不等
Ticket 03，`pptGenerationExecutor`（非渲染阶段）保持现状不动，只做拆分不做调参本身（调参仍留给
Ticket 03，避免重复工作和潜在配置冲突）。

`RenderStrategy`/`ProcessBuilderRenderPort` 的执行提交到 `pptRenderExecutor`，`pptGenerationExecutor`
（或迁移后 `PptWorker` 自身的调度线程）只负责触发这次提交并等待结果，不占同一个池的配额——
这是"渲染是唯一真正吃 CPU/磁盘/子进程的阶段，不应该和纯 LLM 调用的阶段共享同一个线程池配额"这条
要求的具体落地。`pptRenderExecutor` 的线程数即全局渲染并发上限（一个进程内所有用户的 PPT 渲染
共享这一个上限，防止多个用户同时触发渲染把宿主机 CPU/磁盘打满）。

## 5. 独立临时目录 + 保留策略清理

`RenderPort` 实现改成每次调用先创建 `workDir = baseOutputDir/{taskId}/{attempt}`（`attempt`
区分同一个任务因失败重试产生的多次渲染，避免复用上一次失败留下的半成品——延续
`RenderStrategy` 现有注释"每次执行都用一个新的文件名，不复用上一次失败留下的半成品"这条原则，
只是把粒度从"文件名加 UUID"改成"目录级隔离"，同时更方便整体清理），`schemaFile`/`outputFile`
都写在这个目录下。任务终态（`SUCCESS`/`CANCELLED`/最终失败不再重试）之后，按保留策略清理该目录
——具体策略（比如"成功后立即清理本地文件，只保留 MinIO 侧的产物""失败保留 N 天供排查"）在实现时
定，这一票的验收标准是"存在一个清理动作，不是永久堆积"，不要求做成可配置的复杂策略引擎。

## 6. 路径 allowlist

按第 1 节⑤的验证结论，这一票要做两件具体的事，不是泛泛的"加校验"：

1. **产物路径规范化**：`workDir`/`schemaFile`/`outputFile` 的拼接改成先对 `taskId`（数据库自增
   主键，本身安全,不需要过滤）和内部生成的 `attempt`/`UUID` 片段拼接，**不再让
   `conversationId` 参与文件路径拼接**（第 1 节⑤指出的风险点）——`conversationId` 只作为
   `PptGenerationContext` 里的业务字段使用，不进文件系统路径。
2. **模板路径 allowlist**：即使当前只有一份固定模板，这一票要把"模板路径必须落在一个配置好的
   allowlist 目录内"做成一次启动期校验（`TemplateStrategy` 初始化或 `PptGenerationConfig` 装配
   `TemplateStrategy` bean 时校验 `templatePath` 经 `normalize()` 后确实在
   `agenttrail.ppt.template-allowlist-dir`（新增配置项，默认等于当前模板所在目录）之下），
   为将来"按需求选模板"这个已知会来的需求预先钉死安全边界，不是等那个功能真正做的时候才补——
   这是唯一一处这一票主动做"超前"的安全加固，理由是 §2.8 把它列为硬性规则，且成本极低（一次
   启动期路径前缀比较）。

## 7. 产物存 MinIO/S3，数据库只存 ArtifactId，签名 URL

按第 1 节⑥的验证结论：

- `MinioPptImageStore.ensureBucketReady()` 去掉 `setBucketPolicy(publicReadPolicy(...))` 这一段，
  bucket 保持私有（MinIO 默认策略）。
- `downloadAndStore(...)` 的返回值从"完整公网 URL"改成 `object key`（或封装成
  `ArtifactId(bucket, objectKey)`），`PptGenerationContext`/`context_json` 落库的是这个引用，
  不是可直接访问的 URL。
- 新增一个签名 URL 生成方法（MinIO SDK 的 `getPresignedObjectUrl`，短 TTL，比如 15 分钟），
  在 `PptGenerationController.toResponse(...)` 组装响应时才按需生成，前端拿到的是一次性/限时
  链接，不是永久 URL——和 pptx 产物下载（`/agent/v1/ppt/{taskId}/download`，目前是走 Java 进程
  代理读本地文件流）保持一致的"服务端签发访问权限，不暴露永久直链"原则。pptx 产物本身
  是否也要从"本地文件系统 + Java 代理下载"改成"MinIO/S3 + 签名 URL"，取决于第 5 节的清理策略
  最终选型（如果本地文件在任务终态后就清理掉，pptx 产物也必须搬进对象存储，否则清理后无法下载）
  ——**这一票明确要求 pptx 最终产物和配图一样存对象存储**，`/download` 端点收缩成"查询这个
  taskId 对应的 ArtifactId，重定向到签名 URL 或代理转发"，不再直接 `FileSystemResource` 读本地路径。

## 8. `Idempotency-Key`

`PptGenerationRequest` 新增可选字段 `idempotencyKey`（或从 HTTP Header `Idempotency-Key` 读取，
两种方式都符合本票"支持"这个要求，选哪种以 Ticket 15 的 `TaskCoordinator.submit(CapabilityId,
TaskPayload, String idempotencyKey)` 接口形状为准，`TaskCoordinator` 本身已经把
`idempotencyKey` 作为显式参数，`PptGenerationController.create(...)` 只需要把它从请求中取出
透传下去）。`PptTaskStore`（或迁移后统一走 `agent_run` 表）对 `idempotencyKey` 建唯一索引
（`refactor-remediation-ticket-15.md` 第 1 节 `agent_run` 表已经有
`UNIQUE KEY uk_idempotency_key`，如果这一票选择继续用独立的 `ppt_generation_task` 表，
需要给它新增等价的唯一索引），重复提交同一个 `idempotencyKey` 直接返回已存在的 `taskId`，
不新建任务、不重新触发任何 LLM/渲染调用。

## 9. 失败节点按 `RetryClass` 分类

复用 Ticket 12 已经定义的 `com.agenttrail.platform.error.RetryClass`（`NONE`/`RETRIABLE`/
`RATE_LIMITED`/`FATAL`，见 `refactor-remediation-ticket-12.md` 第 2 节）和 `ErrorCode` 类型，
不新发明一套分类体系。`PptGenerationService.run(long)`（或迁移后 `PptWorker`）捕获
`strategy.execute(...)` 抛出的异常时，按来源分类：

| 失败场景 | 分类 | 处理 |
|---|---|---|
| `PptPythonRenderer`/`RenderPort` 报告 Python 进程非零退出码 | `RETRIABLE`（多数情况是瞬时资源问题：内存不足、字体渲染库偶发异常） | 允许 Worker 按重试策略重新调度这个节点（新的 `attempt`，第 5 节的独立目录已经保证不会复用半成品） |
| Python 进程超时（`PptRenderException` "超过 Ns 未结束"） | `RETRIABLE`，但需要限制重试次数（超时往往和输入规模相关，无限重试没有意义） | 同上，附加最大重试次数上限 |
| 模板文件损坏/缺失（`TemplateStrategy` 校验失败，或 `RenderPort` 检测到模板 shape 名称不匹配 schema） | `FATAL`——不是瞬时问题，重试不会变好 | 直接终态失败，不重试，`errorMsg` 明确写出"模板问题"以便运维介入 |
| Schema 校验失败（LLM 产出的 `PptSchema` 反序列化失败或字段缺失，五处重复的"结构化 LLM 调用"模式之一，`SchemaStrategy` 所在阶段） | `RETRIABLE`（重新请求一次 LLM 可能拿到合法结构） | 同 `StructuredLlmCall`（Ticket 09/`refactor-blueprint.md` §2.4 建议的抽象，如果 Ticket 09 已交付，`SchemaStrategy` 应该已经在用它，这一票只是确认它抛出的异常被正确分类，不重复实现解析逻辑）允许重试 |
| 磁盘不足（`Files.createDirectories`/`Files.writeString` 抛 `IOException`，具体是 `FileSystemException`/`IOException` 中"no space left"类消息） | `FATAL` 对当前实例（磁盘满不会因为重试就好），但**不代表整个任务 `FATAL`**——如果 Worker 是多实例部署，任务应该允许被另一个磁盘未满的实例接管重试，这是"节点级 `RETRIABLE`"和"当前 worker 不该继续尝试"两个不同维度的判断，实现时通过租约超时后被其它实例接管的机制自然解决，不需要在这一层额外编码"换实例重试"的逻辑 | 记录为 `RETRIABLE`，依赖 Worker/租约机制的自然故障转移 |

节点失败达到重试上限后的最终失败沿用现有 `taskStore.markFailed(...)` 语义（状态停在当前失败节点
+ 写 `errorMsg`），`PptState` 枚举**不新增** `FAILED`/`RENDER_FAILED`（第 0 节范围边界已明确不
重新设计状态机，`refactor-blueprint.md` §2.2 已经确认这是"明确的设计取舍"）。

## 10. Testing Decisions

- **重复提交**：同一个 `idempotencyKey` 连续 `POST /agent/v1/ppt/create` 两次，断言只创建了一条
  任务记录，第二次请求返回和第一次相同的 `taskId`，且验证只触发了一次 LLM/渲染调用链（用可数的
  mock/fake 验证调用次数，不是只看返回值）。
- **重复 resume**：对同一个 `taskId` 并发调用两次 `/resume`（或模拟多实例分别收到一次
  `/resume`），断言只有一个实际执行了当前节点（通过租约验证），另一个要么被跳过要么等待，
  不产生"同一个节点执行两次、后写覆盖先写"的结果。
- **Worker 崩溃**：模拟 Worker 持有租约执行到一半（比如 RENDER 节点子进程已经启动）被强制终止，
  验证租约到期后另一个 Worker 实例能接管，任务从正确的节点续跑，不重复执行已经成功 checkpoint
  过的节点（延续 `PptGenerationService` 现有"整个状态重新跑一遍"的恢复粒度，验证的是"从正确的
  状态开始"而不是"从零开始"）。
- **Python 超时**：把渲染超时配置调到很短的值触发 `PptRenderException`，验证分类为
  `RETRIABLE` 且触发重试（在最大重试次数内），超过上限后终态为失败且 `errorMsg` 包含超时信息。
- **磁盘不足**：模拟 `workDir` 所在文件系统写入失败（测试里可以用一个只读挂载点或权限受限目录
  模拟 `IOException`），验证任务不会被标记为不可恢复的 `FATAL`，而是保持可被其它实例重试的状态。
- **下载接口未授权访问被拒绝**：这是第 1 节⑧确认的真实漏洞，必须覆盖——未登录请求访问
  `GET /agent/v1/ppt/{taskId}/download`（`taskId` 属于另一个用户）必须返回 401/403/404
  （不能是"匿名请求绕过用户过滤直接下载成功"），已登录但不是任务归属用户的请求同样必须被拒绝；
  这条测试要先在改造前的代码上复现问题（红），再验证修复（绿）。
- **MinIO 私有化验证**：直接匿名 HTTP GET 一个已知的 object key，验证不再能公开访问（改造前
  这条测试应该能复现"公开可读"的现状，改造后应该失败/403）。
- **RetryClass 分类测试**：第 9 节表格里的每一行分类各构造一个测试用例，断言异常被正确归类，
  不是笼统地"失败了就重试"或"失败了就不重试"。

## 验收标准（照抄 `refactor-blueprint.md` §6 Phase 7）

- PPT 不再占用 HTTP 请求线程。
- 同一个幂等键最多创建一个任务。
- 多实例下同一个任务不会重复渲染。

## Out of Scope

- 重新设计 `PptState` 状态机本身（9 个值 + 状态转移顺序不变）。
- DeepResearch/Chat 的迁移（Ticket 17/16）。
- 模板系统改造成"按需求选模板"——这一票只是为这个已知会来的需求预先做好路径 allowlist 校验，
  不实现模板选择逻辑本身。
- 接入除 Python/`render_ppt.py` 之外的其它渲染引擎。
- 磁盘配额的精确阈值、保留策略清理的具体天数等运营参数调优——先给出机制（独立目录+清理动作、
  按渲染阶段隔离的并发池），具体数值留到接入真实流量后再校准，参照 Ticket 15 对 Outbox 重试
  上限/告警阈值的同类处理方式。
- 多 Agent 编排接入 PPT 节点——Ticket 20 范围。
