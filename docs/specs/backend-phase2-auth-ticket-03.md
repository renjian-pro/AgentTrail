# Ticket 3／6：userId 强制注入 Agent 调用链路 + 越权点修复 — 技术开发文档

> 派生自 [`backend-phase2-auth.md`](backend-phase2-auth.md)。`Blocked by` Ticket 1。**不依赖** Ticket 2，可以和 Ticket 2 并行做。这一票是把 P0 越权洞真正堵上的一票，优先级高于 Ticket 2（管理后台）。

## 0. 范围边界

**这一票只改现有代码**，不新增业务功能。改动点严格限定在下表列出的六处，逐条核对，改完一处打勾一处。

## 1. 验收标准

- [ ] `AgentLoopController` 不再硬编码 `"anonymous"`，取 `StpUtil.getLoginIdAsLong()` 转字符串
- [ ] `POST /agent/v1/chat` 未登录访问返回 401（这一步验证 Ticket 1 的全局拦截器和这里的改动接上了）
- [ ] `AgentLoopController.stop(conversationId)`：停止前校验该会话的 `user_id` 匹配当前登录用户，不匹配返回 403
- [ ] `GET /agent/v1/conversations`：只返回当前登录用户自己的会话，换账号看不到别人的
- [ ] `GET /agent/v1/conversations/{conversationId}/history`：同上，非本人会话返回 403/404
- [ ] `agent_file` 表新增 `user_id` 列，`GET /agent/v1/files/{fileId}/content`：非本人文件返回 403/404
- [ ] `ppt_generation_task` 表新增 `user_id` 列，`resume(taskId)`/`download(taskId)`：非本人任务返回 403/404
- [ ] `CapabilityConversationService` 不再用硬编码常量作为落库的 `user_id`，改成从调用方传入真实发起用户
- [ ] 集成测试：账号 A 创建的会话/文件/PPT 任务，账号 B 登录后逐一尝试访问，全部拿不到
- [ ] `RunnableParams.toolParams` 第一次被真正塞入非空值（`userId`），且 `ToolParamInjector` 确实把这个值注入进了工具调用参数（写一个端到端测试验证：一个声明了 `userId` 参数的测试工具，模型侧传入假的 `userId`，断言实际执行时收到的是服务端注入的真实值）

## 2. 六处改动清单（逐条精确定位）

| # | 文件:方法 | 现状 | 改成 |
|---|---|---|---|
| 1 | `web/AgentLoopController.java` chat 入口 | `new RunnableParams(conversationId, "anonymous")` | `String userId = String.valueOf(StpUtil.getLoginIdAsLong());` 然后 `new RunnableParams(conversationId, userId, Map.of("userId", userId), null)`（用四参构造函数，把 `userId` 同时放进 `toolParams`——见第 3 节） |
| 2 | `web/AgentLoopController.java` `stop(conversationId)` | 任何人可停止任何会话 | 停止前查 `agent_session` 该 `conversationId` 最新一条记录的 `user_id`，和当前登录用户不一致时返回 403，再调用 `AgentTaskManager.stopTask` |
| 3 | `web/ConversationHistoryService.java` `findPage`/`findConversations` | SQL 无 `user_id` 过滤 | 两条 SQL 都加 `WHERE user_id = ?`，方法签名加 `String userId` 参数，Controller 层传入当前登录用户 |
| 4 | `web/FileUploadController.java` `content(fileId)` | `fileStore.findById(fileId)` 无归属校验 | 查出文件后比对 `agent_file.user_id` 和当前登录用户，不一致返回 403 |
| 5 | `web/PptGenerationController.java` `resume`/`download` | 无归属校验 | 查出任务后比对 `ppt_generation_task.user_id` 和当前登录用户，不一致返回 403 |
| 6 | `web/CapabilityConversationService.java` `USER_ID` 常量 | 硬编码 `"anonymous"`，PPT/DeepResearch 结果落库统一用这个值 | `recordSuccess`/`recordFailure` 方法签名加 `String userId` 参数，由调用方（`DeepResearchController`/`PptGenerationController`）传入发起请求的真实登录用户；`"deepresearch"`/`"ppt-generation"` 这两个内部标记继续用在子任务自己的 `RunnableParams` 里（区分调用来源，那是运行时内部记账，不是本条要改的落库归属） |

## 3. `RunnableParams` 构造方式（不改这个 record 本身）

`RunnableParams` 是 `loop/model/RunnableParams.java` 里的 record，字段是 `(conversationId, userId, toolParams, outputType)`，已有的四参构造函数直接够用，**不要给它加 `addToolParam` 方法或者任何新字段**——所有需要 `userId` 的调用点，构造时直接传 `Map.of("userId", userId)` 作为 `toolParams`：

```java
String userId = String.valueOf(StpUtil.getLoginIdAsLong());
RunnableParams params = new RunnableParams(
    conversationId,
    userId,
    Map.of("userId", userId),
    null
);
```

这个值会在工具执行前被 `ToolParamInjector.inject`（`loop/core/ToolParamInjector.java:49`）按目标工具的 `inputSchema` 白名单强制写入——**这一票不需要碰 `ToolParamInjector` 或 `ToolCallExecutor`，机制已经在那边接好了，这一票只是第一次真的传了非空值进去**。

## 4. `agent_file` / `ppt_generation_task` 加 `user_id` 列

项目没有 Flyway/Liquibase，`schema.sql` 用 `CREATE TABLE IF NOT EXISTS`，MySQL 不支持 `ADD COLUMN IF NOT EXISTS`。**不要写 `ALTER TABLE`**——按 `schema.sql` 里 `agent_file` 表注释已经写明的先例（issue #27 时的处理方式）：直接改 `CREATE TABLE` 语句本身加上 `user_id VARCHAR(100) NULL COMMENT '所属用户'` 这一列，本地开发库手动 `DROP TABLE agent_file;`／`DROP TABLE ppt_generation_task;` 一次即可让它用新定义重建（项目目前没有生产数据，这是明确允许的做法，不是权宜之计）。同时给这两张表各加一条 `KEY idx_xxx_user (user_id)`。

## 5. 实现顺序

1. 先写越权测试（账号 A/B 互相访问对方资源应该被拒绝），这些测试此时应该是失败的（红）
2. 改 `schema.sql`：两张表加 `user_id` 列，本地 `DROP TABLE` 重建
3. 逐条按第 2 节的表改代码，每改完一条跑一次对应的测试
4. 全部改完，第 1 步写的越权测试应该转绿
5. 最后单独写一个端到端测试验证 `ToolParamInjector` 真的收到了注入值（第 1 节验收标准最后一条）

## 6. 明确禁止事项

- **不要**修改 `RunnableParams` 这个 record 的字段或新增方法
- **不要**修改 `ToolParamInjector`/`ToolCallExecutor` 的实现——这一票只是让已有机制第一次被使用，不是改机制本身
- **不要**用 `ALTER TABLE ADD COLUMN`（MySQL 不支持 `IF NOT EXISTS` 变体，会导致重复执行 `schema.sql` 报错）
- **不要**顺手把 `DeepResearchController`/`PptGenerationController` 内部子任务用的 `"deepresearch"`/`"ppt-generation"` 这两个标记也改掉——那是运行时内部区分调用来源的标记，和这一票要修的"最终落库归属"是两件事，改了反而会破坏现有子任务追踪逻辑
- 其余共享约束（Testcontainers、中文注释、不点名来源）同 Ticket 1
