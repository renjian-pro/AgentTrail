# 会话轮次模型：文件绑定、异步任务留痕、能力模式 — 需求与技术方案

> 状态：草案（2026-08-17 讨论产出），按 `to-spec` 模板整理。
> 上位文档：[`requirements.md`](../requirements.md) §7「能力入口与会话模型」——**§7 已于 2026-08-17 按本文第 3 章修订**（三层模型 → 互斥模式 + 跨模式历史压平；R13/R17 重写，新增 R19/R20/R21）。冲突时仍以 `requirements.md` 为准。
> 相关：[`frontend-capability-model/`](frontend-capability-model/)（F6/F7/F8）——**建立在旧的三层模型上，F7 已作废、F6 需重开范围**。

## 0. 这份文档解决什么问题

三件事，共同的主题是**"一轮问答"的边界到底由什么定义**：

1. **文件绑到轮次，靠的是"扫一遍会话里没绑过的"，不是用户点了发送。** 上传接口一返回成功，文件就已经进了下一轮的系统提示词，跟用户有没有点发送、有没有在输入框里删掉它，完全无关。
2. **DeepResearch / PPT 的产物不在会话历史里。** 它们有各自的 controller 和任务表，`agent_session` 完全不知道它们存在，左侧会话列表看不到。
3. **`mode` 字段只认一个值，其余静默降级。** `"analytics"` 以外的任何取值（包括拼错的 `"Analytics"`）都会安静地按普通聊天跑完，不报任何错。

---

## 1. 文件绑定：从隐式 sweep 改为显式 fileIds

### 1.1 现状

绑定在**两个地方都按 `conversation_id` 兜**，"发送"这个动作不参与：

| 层 | 代码 | 行为 |
|---|---|---|
| 模型可见性 | `AgentLoopExecutor.java:1070`<br/>`fileStore.findByConversationId(conversationId)` | 按会话查全部文件，**不看 `turn_id`** |
| 轮次归属 | `AgentLoopExecutor.java:1106`<br/>`fileStore.linkFilesToTurn(conversationId, turnId)` | 把会话里所有 `turn_id IS NULL` 的行一扫而空 |

`AgentChatRequest` 里没有 `fileIds` 字段，只有 `message / conversationId / modelId / webSearchEnabled / mode`。

### 1.2 由此产生的四个真实缺口

不是理论隐患，四条现在全都成立：

1. **"发送前删除"只是视觉效果。** 前端把 chip 拿掉，文件在 DB 里还是 `turn_id IS NULL`，下一轮照样进系统提示词、照样能被 `load_file_content` 读到。要真删只能调 DELETE 硬删行——网络抖一下没删成，文件就静默混进下一轮。
2. **多标签页互吞。** 同一会话在两个标签页开着，A 页上传、B 页发送，B 页那轮会把 A 页的文件吃掉。
3. **跨会话时间的静默附加。** 上传完没发送就离开，下次进这个会话随便问一句，上次的文件会被悄悄带上。
4. **无法部分选择。** 传了 3 个文件只想附 2 个，做不到。

### 1.3 决策

**发送时由前端显式传 `fileIds`，绑定以它为准。**

```
t1  上传        → agent_file 插行，turn_id = NULL
t2  点发送      → POST /chat { message, fileIds: [12,13] }
                  后端校验 12/13 属于当前 user + 当前 conversation
                  → buildFileSection 把它们放进本轮系统提示词
t3  流结束      → agent_session 插行，拿到自增 id = 88
t4  Complete    → linkFilesToTurn([12,13], 88)
                  → UPDATE agent_file SET turn_id = 88 WHERE id IN (12,13)
```

`turn_id` 在 t1 只能是 NULL 是物理约束，不是设计缺陷：`agent_session` 那行的 `answer` / `think` / `timeline` / `total_response_time` 全要等流结束才有值，而 `id` 是自增主键，插入之前不存在。这一点 schema.sql:15 已有说明，保持不变。

### 1.4 `agent_session` 不加 `file_ids` 列

一对多的外键就该在"多"那一侧。`agent_file.turn_id` 已经是"这一轮附了哪些文件"的完整答案，在 `agent_session` 上再存一份是把同一条关系存两份，两份必然漂移。

而且存成逗号分隔串或 JSON 数组，不可 JOIN、不可索引、加不了约束，还答不了回放真正要问的反向问题——"这个文件属于哪一轮"。

**`fileIds` 只活在传输层**：`AgentChatRequest` 上的一个字段（意图），落库形态就是 `turn_id`（结果）。

### 1.5 文件不进 `question` 列

`question` 是用户输入的那段文字，仅此而已。文件名塞进去会污染两处：历史回放喂给模型的文本、golden case 抽取的样本。

"一轮"在 UI 上是一个气泡，在存储上是两张表：`agent_session` 一行 + `agent_file` N 行（`turn_id` 指回来）。

### 1.6 必须一起付的代价：t3/t4 要在同一个事务里

旧的隐式 sweep 有个**没人注意到的自愈副作用**：扫的是所有 `turn_id IS NULL`，这一轮绑失败了，下一轮会顺手扫走。

改成按 `fileIds` 精确绑之后，这个兜底消失。`AgentLoopExecutor.java:1100-1107` 现在是两次独立调用（`persistenceHook.onTurnComplete` 拿 turnId，再 `linkFilesToTurn`），中间失败会留下"轮次落库了、文件永远 NULL"的中间态，**且没有任何后续路径会再修它**——那个文件从此在历史回放里消失，虽然行还在表里。

**这两步必须包进同一个事务。** 这是从隐式改显式的必付代价，不是可选优化。

### 1.7 改动清单

- `AgentChatRequest` 加 `List<Long> fileIds`
- 归属校验：每个 id 属于当前 user + 当前 conversation（否则是一个越权读别人文件的口子）
- `linkFilesToTurn(conversationId, turnId)` → 按 `fileIds` 绑
- **`buildFileSection` 可见性口径跟着改**：改成「本会话里已绑定到某一轮的文件 + 本轮传进来的 `fileIds`」。**不改这条，1.2 的四个缺口一个都堵不上**
- 签名扩散：`FileStore` / `FileStorePort` 两个接口，`JdbcFileStore` / `InMemoryFileStore` / `LegacyFileStoreAdapter` 三个实现
- `onTurnComplete` + `linkFilesToTurn` 包进同一事务

### 1.8 顺带暴露的既有缺口：`AttachmentStatus` 没落库

`Attachment` 领域对象有 `AttachmentStatus`（PENDING/READY/FAILED）和 `errorCode`，但 **`agent_file` 表里没有 `status` / `error_code` 列**。`LegacyFileStoreAdapter.fromLegacy` 读出来硬编码 `AttachmentStatus.READY`，`FileStorePort.markFailed` 是 default 空实现，注释写着 *"Legacy stores have no status/error columns"*。

改成显式传 `fileIds` 之后，"用户点发送时这个文件还在解析 / 已解析失败"成了后端必须能回答的问题——大 PDF 的 Tika 解析不是瞬时的。而现在数据库答不了。

**待定**：要不要一起补 `status` + `error_code` 两列。不补的话只能靠前端在上传接口返回后才允许发送，兜不住解析失败的情况。

### 1.9 两个待定项

| # | 问题 | 倾向 |
|---|---|---|
| A | 孤儿行怎么清（上传了从没发送的，带着 `parsed_text` 全文和 `raw_bytes` 图片字节，不小） | 保留 DELETE 端点给显式删除 + 定时清 `turn_id IS NULL` 且超过 N 天的 |
| B | 跨轮可见性保留吗——第 1 轮传的 PDF，第 5 轮还能不能被模型看到 | 保留。这是 `conversation_id` 那列注释"跨轮可见"的原意。区别只是：**绑定过的才可见，没绑定过的不可见** |

---

## 2. 异步任务在会话里留痕

### 2.1 现状

| 能力 | 入口 | 协议 | 持久化 |
|---|---|---|---|
| 普通聊天 | `/agent/v1/chat` | SSE 单次流 | `agent_session` |
| 数据分析 | 同上 + `mode=analytics` | SSE 单次流 | `agent_session` |
| DeepResearch | `/agent/v1/deepresearch` | POST 建任务 + SSE + cancel | **内存**（`InMemoryResearchArtifactStore`，`ResearchArtifactStore` 唯一实现） |
| PPT | `/agent/v1/ppt/create` | POST 建任务 + 轮询 + cancel + resume + download | `ppt_generation_task` |

后两者的产物不在 `agent_session` 里，左侧会话列表看不到。

### 2.2 决策：`agent_session` 加 `mode` + `task_ref`，不加 status

DR/PPT 任务创建时往 `agent_session` 写一行，只存 `question + mode + task_ref(taskId)`。

**行必须在任务创建时就写**，不能等完成——否则任务跑着的时候刷新页面，历史里空空如也。

**那一行不存状态**，状态永远回查任务表（`ppt_generation_task.status`）。理由：异步任务上双写状态的不一致不是"可能发生"而是"必然发生"——进程被 kill、cancel 打在两次写中间、resume 续跑，都会留下 `agent_session=RUNNING` 而 `ppt_generation_task=CANCELLED` 的僵尸行。代价是历史列表多一次批量回查，值。

好消息：`ppt_generation_task` 已经有 `conversation_id` 列和 `idx_ppt_task_conversation`，反向关联现成。

### 2.3 硬前置：DeepResearch 必须先落库

`InMemoryResearchArtifactStore` 是 `ResearchArtifactStore` 的唯一实现，整个 `capability/deepresearch/`（16 个类）零持久化。应用一重启，`task_ref` 就指向空，历史里永久留一张加载失败的卡片。

对照 PPT 的五件套（`status` / `error_msg` / `cancel_requested` / `context_json` / `conversation_id`），DR 一件都没有。

**这不是可选项，是 2.2 能不能做的前提。**

### 2.4 加列走既有的幂等 ALTER 模板

schema.sql:117-142 已有一套模板在用（`agent_trace` 补三列都走的这个）：

```sql
SET @ddl := IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_session' AND COLUMN_NAME = 'mode') = 0,
    'ALTER TABLE agent_session ADD COLUMN mode VARCHAR(20) NULL COMMENT ''...''',
    'DO 0');
PREPARE add_mode FROM @ddl;
EXECUTE add_mode;
DEALLOCATE PREPARE add_mode;
```

**两边都要改**：本地/测试走 `schema.sql`（幂等 ALTER），生产走 `db/migration/V3__xxx.sql`（Flyway，现在到 V2）。schema.sql:1-4 明说两份不自动同步。

`prompt_stamps` 那次踩的坑就是只写了建表语句、留了句"请手动执行"的注释，没有任何路径真的执行，每次 trace 落库都 `Unknown column`。

---

## 3. 能力模式：互斥、逐轮可切

> §7 已按本章修订（2026-08-17）。本章只记要点，完整论证见 [`requirements.md`](../requirements.md) §7.2/7.3。

### 3.1 模型

交互层是一排互斥模式（普通对话 / 数据分析 / 深度研究 / PPT），同一时刻只能选一个，在同一个会话里随时可切。协议仍是两类：前两者走 `/agent/v1/chat` 的 SSE 单次流，后两者走各自的异步任务链路。**入口统一 ≠ 协议统一。** 映射由前端一张 `mode → api` 表承担，**不新增统一调度层**。

**选中语义**：选中后保持，直到用户手动取消或切到另一个模式，绝不因发送而复位。"取消"＝切回普通对话。枚举里普通对话是显式取值 `chat` 而不是 `null`——`null`（没传）和未知值（传错）必须可区分，否则 3.3 的 400 判定无从下手。

**切换模式要重新装载三样，两样已经是了，一样不存在**：

| | 现状 |
|---|---|
| 工具集 | ✅ 每轮按 `mode` 选执行器变体，选择动作每轮发生 |
| 上下文 | ✅ 每轮 `loadHistory` 从 DB 重建，无内存态 |
| 系统提示词 | ❌ `ContextAssembler.assemble(null, ...)`——`systemPrompt` 参数恒为 `null`。每轮只有三条与 `mode` 无关的 SystemMessage（日期 / 记忆 / 会话文件），四个模式共用空骨架。见 **R22** |

### 3.1.1 联网搜索与模式不正交

不是一个统一的叠加开关，四个模式各不相同：

| 模式 | 联网搜索 | 依据（代码事实） |
|---|---|---|
| 普通对话 | 用户可开关 | 有隐私/成本权衡要留给用户 |
| 数据分析 | **禁用** | `forAnalytics(String modelId)` 不接 `webSearchEnabled` 参数 |
| 深度研究 | **内建，非开关** | workflow 本身就是检索驱动 |
| PPT 生成 | **内建，非开关** | `capability/ppt/strategy/SearchStrategy` 固定两个互补角度收集素材喂给 `OUTLINE` 状态，关掉就没素材 |

**UI 结论**：搜索开关只在普通对话下出现。在 DR/PPT 下渲染成"已开启的开关"是错的——那暗示可以关掉，而关掉这两个模式跑不起来。

### 3.2 跨模式的 `tool_calls`：故障当前不存在，要做的是钉住它

放弃会话级绑定之后，原推导链的第 4 步看起来会暴露出来：

> 换执行器 → 工具集在会话内逐轮变化 → 消息历史里的 `tool_calls` 会指向当前这一轮不存在的工具

**核实实现后：不成立。** `JdbcSessionStore.java:98` 的 `loadHistory` 只 SELECT 两列——

```java
.query((rs, rowNum) -> new TurnSummary(rs.getString("question"), rs.getString("answer")))
```

`timeline`（工具调用时间线 JSON）落了库但从来不读回来。跨轮历史重建出的是纯粹的 UserMessage / AssistantMessage 对；`tool_call` / tool result 消息只活在**单轮 ReAct 循环内部**（暂停恢复的 `PauseState` 同样只在轮内）。

所以"数据分析完切回普通对话"，模型看到的是：

```
User:      上季度华东销售额多少
Assistant: 上季度华东销售额 1,240 万，环比 +8.3%
User:      （新问题）
```

没有 `execute_sql`，没有 `tool_call_id`。**工具集的稳定作用域天然就是"一轮"。**

**真正的风险是这条契约没人声明。** "让模型看到自己上一轮查了什么"是个很自然的优化想法，谁哪天把 `timeline` 读回来，第 4 步立刻成真，且只在切过模式的会话里复现。

**R17 = 钉住，不是新建**：`loadHistory` 上写明约束与理由 + 一条回归断言（构造「数据分析轮 → 切普通对话追问」，断言历史里不含任何 `tool_call_id`）。成本几行，不是一套机制。

### 3.2.1 为什么工具不能改成"写在 Skill 里"

一个自然的简化想法：既然模式差异主要是工具，那把工具声明在 SKILL.md 里，靠 Skill 加载带出来，不就不用换执行器了？

**做不到**：`SkillManager.buildSkillsTool()` 返回的是**一个** `ToolCallback`（`SkillsTool` 元工具），模型调它拿回一段 SKILL.md 正文。Skill 不携带工具。CONTEXT.md 的定义：*Skill 决定"怎么做"，Tool 提供"能做什么"*。

**更不该做**：真要让 Skill 带工具，工具列表就变成运行期由模型选哪个 Skill 决定——**等于把工具授予权交给模型**，正好违反 §7.1 原则 2「代码是硬边界，提示词是软约束」，而这条原则的证据就是踩坑点 #29。装配期确定的边界模型绕不过；运行期由模型自选的边界等于没有。

**而且工具只是差异的一部分**：`CapabilitySpec.analytics` 与 `chat` 还差着 `maxRounds` 20 vs 10、`maxConsecutiveToolFailures` 3 vs 0、`memory` 关、`files` 关——这些写不进 SKILL.md。

现有分工是对的，保持：**工具在装配期由 `mode` 决定（硬边界），SOP 在运行期由 Skill 按需加载（软指导，R2）**。

### 3.3 `mode` 的两项改造

**既要传也要存。** 前端每轮显式传，决定这一轮怎么跑；`agent_session.mode` 落库，决定历史回放时这一行渲染成 `ResearchReportCard` / `PptTaskCard` / 普通消息。传了不存，刷新页面后卡片退化成纯文本。

**静默降级必须修。** `ChatApplicationService.java:54` 是 `"analytics".equals(mode)`，其余取值（含 null、含拼错的 `"Analytics"`）一律按普通聊天跑完，不报错。取值从 1 个扩到 4 个之后，这会变成很难查的线上问题。**改成枚举 + 未知值 400。**

---

## 4. 依赖顺序

```
1.x 文件绑定（R21）──────────── 独立，无前置

3.2 钉住 loadHistory 契约（R17）── 独立，无前置（注释 + 一条断言）

3.3 mode 枚举化（R13a）──┐
                         ├─→ 2.2 会话留痕（R19）
2.3 DR 落库（R20）───────┘
```

**关键路径**：`(3.3 ∥ 2.3) → 2.2 会话留痕`。只有这一条有依赖。

其余三块——**1.x 文件绑定**、**3.2 钉契约**、以及 3.3/2.3 本身——互不相干，可并行开工。

风险集中在两处，都在 1.x：

- **1.6 事务边界**：改成显式 `fileIds` 后失去隐式 sweep 的自愈，`onTurnComplete` 与 `linkFilesToTurn` 之间失败会留下不可自愈的中间态
- **1.7 可见性口径**：`buildFileSection` 不跟着改，1.2 的四个缺口一个都堵不上

## 5. 范围外

- 不新增能力调度层（`CapabilityRouter` / `ModeDispatcher` 之类）。全部改动落在现有表和现有 `mode` 字段上，符合 2026-08-16 架构评审"先做减法不再加层"的结论
- 不动 `agent_file` 的双 key 设计（`conversation_id` + `turn_id`），schema.sql:8-17 的理由仍然成立
- 不改 DR/PPT 各自的任务协议（create/poll/cancel/resume/download）。入口统一 ≠ 协议统一，SSE 单次流承载不了"刷新页面还能看进度""断点续跑""产物下载"
