# AgentTrail 完整路线图（不设时间上限，按阶段推进）

> 配套 ADR：[0001](adr/0001-runtime-scope-and-stack.md)（Runtime 定位、LlmClient 抽象，仍有效）、[0002](adr/0002-hand-rolled-loop-as-v1-mainline.md)（手写 loop 是 V1 主线，AgentScope Java 2.0 延后为有计划的后续阶段）。
>
> 这是 AgentTrail 完整能力集（Runtime 核心 + SQL 数据分析 + 深度研究 + PPT 生成 + 多 Agent 编排）的落地路线，按"依赖关系"而不是"日期"排阶段。每一项机制的具体做法都来自对真实企业级 Java Agent 项目源码 + 设计笔记的研读（见 `engineering-pitfalls-and-highlights.md` 的踩坑点，实现时逐条对照）。自己根据实际进度增减、加速。

## 技术决策（已定，不再讨论）

> **2026-08-03 交叉核对补充**：已核对现有设计与真实生产形态 DataAgent 实现的对照结果，详见内部笔记和 [ADR 0003](adr/0003-agentscope-isolated-data-agent-runtime.md)。DataAgent 采用 AgentScope 仅限于隔离的 Runtime Adapter；M-Schema/YAML、SQL AST 安全、数据权限重写、脱敏、结果校验和 Artifact 仍属于 AgentTrail 业务/平台层。认证与租户主体必须先于 SQL 能力包，不能继续让生产路径使用 `anonymous`。

- **模型接入直调 `ChatModel.stream(Prompt)`，不经过 `ChatClient`/Advisor 链**——`internalToolExecutionEnabled` 只存在于 ChatClient 这一层，绕开它就不需要关心 Spring AI 1.1.0/2.0 的差异，两个版本行为一致。**结论：上 Spring AI 2.0 GA**（原生匹配 Boot 4.1.0）。
- **版本映射提醒**：研读的参考框架源码基于 **Spring AI 1.1.0 + Boot 3.5.6**，本项目是 **Spring AI 2.0 GA + Boot 4.1.x**——研读时对 ChatClient/Advisor/Tool 注册相关的 API 要做版本映射笔记（1.x 的 `internalToolExecutionEnabled` → 2.0 的 `ToolCallingAdvisor`；Function Bean → 显式 `ToolCallback` Bean；`spring.ai.*.chat.options.*` 配置扁平化），面试被追问"2.0 和 1.x 区别"时这本身就是答案素材。
- **SQL 场景库用 sakila**（MySQL 官方 DVD 租赁库，通用示例数据集）。
- **不删除** V0（`AgentLoop`/`AgentScopeRuntime` 两条早期路径），但收敛进单一文件 `com.agenttrail.legacy.V0`，作为决策演进的对比参考保留，不再演进；`agentscope-bom` 依赖同理保留。

---

## Phase 0：Runtime 核心（ReAct Loop 基础设施）

设计已在验证原型上写出一版并跑通，本阶段用 Spring AI 2.0 在本项目里重新实现 + 验证。

### 验证进度速览

哪些机制已经在验证原型上跑通过设计、哪些还只是纸面设计，实现本项目时按这个状态决定"直接照设计写"还是"设计和实现一起做"：

> **2026-07-31 更新（第三次修正）**：issue #15-#19 已全部关闭，补齐了上一次修正发现的
> 0.11-0.15 五项engine缺口（同步调用、分层记忆中间层、分阶段输出、TraceAudit、结构化输出
> 基础机制）。至此 **Phase 0 + Phase 1 完整做完**——引擎已经和参考框架 `docs/core/` 的 19 篇
> 能力文档对齐（分层记忆的语义摘要层、结构化输出的 Reviewer 二次确认、SubAgent 的业务化演示
> 三处例外，前两处按设计留给 Phase 4/Phase 3，最后一处只是缺少 Capability Pack 场景来演示，
> 机制本身随时可以补）。下一步进 Phase 2（SQL 数据分析能力包），不用再犹豫"引擎是不是真的
> 做完了"。
>
> 每行的 issue 号可以在 `gh issue view <n> --json state,body` 里查到验收标准。

| 阶段 | 机制 | 状态 |
|---|---|---|
| 0.1 | 流式 tool_call 分片重组、round 状态机、maxRounds 强制收尾 | ✅ 已验证（issue #1） |
| 0.1 | 参数校验失败降级、`RunnableParams` 双通道 | ✅ 已验证（issue #1） |
| 0.2 | Thinking 模式三分支、`ThinkTagParser` | ✅ 已验证（issue #3） |
| 0.2 | DeepSeek `reasoning_content` 装饰器（#5a 两个类型坑） | ✅ 已实现（issue #2/#3），流式行为待拿真实 key 补一次实测（`DeepSeekLlmClientLiveIT`） |
| 0.3 | 两层上下文压缩（micro/auto） | ✅ 已验证（issue #4） |
| 0.4 | 任务管理（内存版：单飞注册、`Disposable` 每轮重注册、原子 stopTask） | ✅ 已验证（issue #1），跨实例生产化见下方 Phase 1 行 |
| — | Runtime 门面（对外 builder 装配入口） | ✅ 已验证 |
| 0.5 | 会话持久化（`TurnPersistenceHook` + JDBC 实现） | ✅ 已完成（issue #5） |
| 0.6 | ToolSearch 延迟工具发现 | ✅ 已完成（issue #6） |
| 0.7 | Skills 渐进式披露（单 mega-tool） | ✅ 已完成（issue #7） |
| 0.8 | TodoWrite | ✅ 已完成（issue #8） |
| 0.9 | FileSystem/Bash/Grep 内置工具 | ✅ 已完成（issue #9） |
| 0.10 | Reactor 流式工程加固（有界背压、TTFT/idle 分阶段超时、工具执行独立调度器、MDC 跨线程传播） | ✅ 已完成（issue #10） |
| 0.11 | 同步（非流式）调用（对应 core/01；`call()` 直接包一层 `stream()`，复用单飞注册/上下文压缩等全部既有机制） | ✅ 已完成（issue #15） |
| 0.12 | 分层记忆体系中间层（画像/偏好/指令/事实，对应 core/05；短期历史层见 0.5，跨会话语义摘要层留给 Phase 4） | ✅ 已完成（issue #19） |
| 0.13 | 分阶段输出 / Timeline（`StageOutputProvider` SPI + 循环在固定生命周期点调用，对应 core/06） | ✅ 已完成（issue #16） |
| 0.14 | TraceAudit 追踪审计（每轮 Prompt/工具调用/耗时/Token 落库，对应 core/17；内存 + `JdbcTraceStore` 两种实现） | ✅ 已完成（issue #17） |
| 0.15 | 结构化输出（JSON Schema 注入 + 自动修复，对应 core/13 的基础机制部分） | ✅ 已完成（issue #18）。"高危输出走 Reviewer Agent 二次确认"仍然是 Phase 3 的业务层判断（见下方 Phase 3 表），这条基础机制是它的前置依赖 |
| 1 | Redis 分布式任务锁 / Pub-Sub 跨实例中断 | ✅ 已完成（issue #11/#12）。`RedisTaskLock.startAutoRenewal()` 显式开启后台续期，解决了"长会话跑得比 TTL 久，锁在还活着的时候过期被抢"这个缺口 |
| 1 | 断点续传 / HITL 暂停恢复 | ✅ 核心机制已完成（issue #13）：`PauseState` 快照 + 两条恢复分支都有测试覆盖。内存 + `JdbcPauseStateStore` 两种实现，整份快照序列化成 JSON 落库 |
| 14 | 幂等工具模板 | ✅ 已完成（issue #14） |
| 8* | SubAgent 子代理（`call_{name}` 工具包装，对应 core/18） | ❌ 未实现。*机制本身通用、无 Capability Pack 前置依赖，随时能做；Phase 8 的"等 2-3 个能力包"只对"拿真实业务 Agent 演示"成立 |

### 0.0 测试基建（先于一切——AgentTrail V0 已有雏形，直接升级）
- `ScriptedLlmClient`（回放固定 LLM 响应序列）+ `RecordingTool`（记录工具调用序列做断言）——**流式 Agent 唯一能做确定性单测的手段**，V0 已经写过，实现新 loop 时同步升级适配新的 `AgentLoopExecutor`
- 流式分片重组模糊测试：把一个完整 tool_call JSON 随机切成 N 片喂给 `mergeToolCall`，断言能正确还原（对应踩坑点 #1 的工程级验证）
- 集成测试用 Testcontainers 起真实 MySQL(sakila)/Redis，**禁用 H2 mock**——SQL AST 校验、权限改写在 H2 上行为和 MySQL 不同，会掩盖 bug

### 0.1 手写 ReAct loop（设计已验证，待在本项目实现）
- 流式 tool_call 分片重组：按 `id` 累加器合并，纯字符串拼接 `arguments`，校验放到整轮结束后统一做（踩坑点 #1）
- round 状态机：`processChunk`（分类文本/工具调用 chunk）→ `finishRound`（无工具调用即终局，有工具调用则执行后递归下一轮）
- 达到 `maxRounds` 强制收尾：换一个不带任何工具的 ChatClient/请求，逼模型直接给文字答案
- 参数校验失败降级为空参数而不是抛异常崩溃整个 loop（踩坑点 #2）
- `RunnableParams` 动态参数双通道：prompt 参数（模型可见）vs toolParams（模型不可见，工具执行前由框架强制注入覆盖，且按目标工具 inputSchema 白名单过滤——Phase 2c 的 userId 强制固化依赖这个机制）（踩坑点 #59）
- **构建期提醒**：`AssistantMessage`/`ToolResponseMessage` 的多参构造函数在验证原型所用的 Spring AI 版本上是 `protected`，只能走各自的 `builder()` 构造——本项目用 Spring AI 2.0，实现时第一件事就是确认这一点是否依旧成立，不要假设能直接 `new`

### 0.2 Thinking 模式分流（设计已验证，待在本项目实现）
- `REASONING_CONTENT`/`THINK_TAG`/`DISABLED` 三分支，对应 DeepSeek/Qwen（独立字段）、MiniMax（`<think>` 标签混排）、无思考模型
- `ThinkTagParser` 跨 chunk 状态机处理标签被截断的情况（踩坑点 #3）
- reasoning_content 提取走 metadata key 优先、反射兜底，反射 `Method` 按 Class 缓存（踩坑点 #4）
- ~~**阻塞项**：DeepSeek `reasoning_content` 强制回传要求在 Spring AI 2.0 上先验证是否已修复~~ → **已调研，结论：部分修复**（踩坑点 #5a）。Spring AI 2.0.0 把"序列化时回传"这一半修好了（1.x 到 1.1.8 都没修），但只在历史消息仍是 `DeepSeekAssistantMessage` 实例时生效；**流式 + 工具调用**这条路上聚合器会把子类型抹平，reasoning 照样丢，手写 loop 自己拼的 `AssistantMessage` 同理。所以：
  - **ChatModel 装饰器仍然要写**，但职责收窄成"保证放进历史的消息带着 reasoning + 修掉 2.0 新增的两个类型坑（`text != null` 断言、options 硬转 `DeepSeekChatOptions`）"，序列化交给 SDK
  - **`ThinkingModeProcessor` 只读 metadata 的取舍不变，但装饰器必须把子类字段规范化进 metadata**，否则对接 DeepSeek 官方模块时一个 `Thinking` 事件都发不出来
  - **不要走 OpenAI 兼容端点访问 DeepSeek 的 thinking 模式**：2.0.0 上 OpenAI 模块流式/非流式都拿不到 `reasoning_content`，修复在未发版的 2.0.1 里
  - **剩余待实测**：服务端到底在什么条件下回 400，只能拿真实 key 验证，方案见踩坑点 #5a 的第 ④ 段。这一项不再阻塞 Phase 0 动工——先按上面的装饰器方案实现，实测用来确认而不是用来决定设计

### 0.3 两层上下文压缩（设计已验证，待在本项目实现）
- micro_compact（结构不变、老工具内容/参数截断成 JSON 占位符，保留最近 N 条原文）+ auto_compact（超 token 阈值整体摘要替换），GC 分代类比
- 占位符必须是合法 JSON、两个方向都要压、摘要不能对工具内容二次截断（踩坑点 #6/#8）
- 为什么从"选择性保留最近 N 条"改成"全部压缩"：边界对齐问题 + 认知断层（踩坑点 #7）

### 0.4 任务管理（内存版，设计已验证，待在本项目实现；Redis 化见 Phase 1）
- 单飞注册（`putIfAbsent`）+ `Disposable` 每轮重新注册（踩坑点 #9）+ `stopTask` 原子 `remove`（踩坑点 #10）

### 0.5 会话持久化（接口已定义，JDBC 实现待写）
- `TurnPersistenceHook` 接口：loop 结束时同步回调一次，拿到 `sessionId` 塞进 `Complete` 事件
- JDBC 实现：写 `question`/`answer`/`think`/`timeline_json`/`created_at`；历史消息重建按 `conversation_id` 查最近 N 轮拼回 `Message` 列表——N 不能拍脑袋定死，按 token 预算倒推（用 `TokenEstimator` 估算，超预算从最老的轮次截断）
- **持久化时序**：必须在 `tryEmitComplete()` 之前同步完成入库，不能放在 `doFinally` 里（踩坑点 #63，参考框架源码里真实踩过：doFinally 时序问题导致 JVM 退出时历史丢失）
- 会话表用 `conversation_id`（跨轮可见）+ `session_id`（历史回放归属）两个独立 key——Phase 4 的文件生命周期依赖这个结构，一开始就按这个建表（踩坑点 #49）

### 0.6 ToolSearch 延迟工具发现（雏形已写，待完善）
- HYBRID 模式：关键词打分优先（tokenize + 加权评分），**只有关键词搜索零命中时才 fallback 到 LLM 搜索**，不是两套都跑
- `DeferredToolRegistry`：常驻的工具池 + 预分词 catalog（只建一次，避免每次请求重新分词）；每次请求创建全新 `Session`，`discoveredNames` 请求级隔离、只增不减（踩坑点 #13/#14）
- 关键机制：发现工具名不等于能调用——真正生效要靠**下一轮重建 ChatClient/请求的工具列表**，这也是为什么 Phase 0.1 的 loop 设计成"每轮都重新组装工具列表"而不是构造时固定死

### 0.7 Skills 渐进式披露（待实现）
- **工具设计**：单 mega-tool（`SkillsTool`），不是 read_skill 双工具模式——全部技能的 frontmatter 渲染成 XML 直接写进这一个工具自己的 description，模型"看到工具描述里列着技能→调用这个工具"，不会把技能名当成独立工具乱调（踩坑点 #15，这是一次真实的设计迭代，值得讲）
- **管理层（`SkillManager`）**：双存储——文件系统是 `SKILL.md` 内容的唯一真相，DB 表只存 `name`/`skill_path`/`description`/`enabled` 这些查询用元数据
- **上传流程**：zip 解压临时目录必须建在目标 skills 目录**下面**，不能用 OS 默认临时目录（跨盘符 move 非空目录在 Windows 下会抛异常，踩坑点 #16）；skill 名字要做路径穿越校验
- **热更新**：`@Scheduled` 定时对账文件系统↔DB（发现新增/清理孤儿记录），且**每次对话请求都重新从 DB 读取当前启用的技能目录**动态构建 `SkillsTool`，运营人员切换启用状态下一轮立刻生效、不用重启（踩坑点 #48）
- **已知生产坑**：代码生成密集型技能场景（比如让模型写大段脚本作为工具参数）如果 `maxTokens` 设太小，会截断工具调用参数导致看似"参数为空"的诡异 bug，根因其实在 token 上限（踩坑点 #47）

### 0.8 TodoWrite（待实现）
- `todo_write` 工具，模型每次调用都提交完整任务列表（不是增量 diff），校验"同时只能有一个 in_progress"
- 每次调用后重新解析原始参数 JSON（不依赖工具返回值）发出独立的 `TodoProgress` 事件，前端渲染任务清单进度
- 动机：多步骤任务在纯 ReAct 模式下会漏步骤/顺序乱/进度不可见/忘目标，解法是先显式外部化任务状态再执行（踩坑点 #58）

### 0.9 文件系统 / Bash / Grep 工具（待实现，Runtime 常驻工具三件套）
- `FileSystemTools`（read/write/edit/list/glob）：`virtualMode` 目录白名单沙箱、`edit_file` 唯一性校验、三级编码兜底、`write_file` 文档与实现不一致的已知 bug 处理决策（踩坑点 #17-19）
- `BashTool` + `ShellSessionManager`：伪持久 shell（记录 `lastDirectory` 拼接 `cd` 前缀，不维护长活进程）、双重输出截断（踩坑点 #20）
- `GrepTool`：ripgrep 优先、纯 Java 兜底
- 这一项也是 Phase 9 MCP Server 化的安全前置（目录白名单沙箱在对外暴露时是最低安全线，踩坑点 #41）

### 0.10 Reactor 流式工程细节（Java 工程深度的集中体现，面试并发主线素材）
- **背压有界化**：`Sinks.many().unicast().onBackpressureBuffer()` 无上限 buffer 在"长输出+慢消费端"场景有 OOM 风险，生产要换有界 buffer 或 `limitRate()`（踩坑点 #64）
- **超时分阶段**：thinking 模型首 token 延迟远高于后续 token，超时不能一刀切——TTFT 超时和 token 间隔超时分开设；且 `timeout` 操作符只断下游，要配合 `cancel()` 真正释放上游订阅
- **线程池隔离**：工具并发执行和流式聚合如果共用 `Schedulers.boundedElastic()`（默认 10×CPU 上限），高并发下互相阻塞，拆独立 `Schedulers.fromExecutor` 池（踩坑点 #62）
- **上下文传播**：MDC/trace 上下文跨线程用 Reactor Context / Micrometer ContextPropagation（踩坑点 #39）。对照：另一种常见方案是 TransmittableThreadLocal（`AgentSessionContextHolder`，TTL 自动传播到 boundedElastic 线程）——两种方案的取舍（Reactor 原生 vs TTL 侵入性低但依赖 agent 包装线程池）本身是面试可讲的对比题；研读过的参考框架源码两者都没用（其上下文靠显式参数传递）

---

## Phase 1：Runtime 生产化（多实例 / Redis）

| 机制 | 说明 |
|---|---|
| Redis 分布式任务锁 | Redisson SETNX + instanceId 归属校验续期（原子操作，踩坑点 #30）+ TTL 自愈 + 优雅关闭主动释放（踩坑点 #31） |
| Pub/Sub 跨实例中断 | 本地 map 快路径 + Redis 广播兜底，前端 `AbortController` + HTTP 双路径（踩坑点 #12） |
| Redis 会话/状态存储 | 会话历史、pause-state（如果后面做断点续传）迁移到 Redis/MySQL，不再是纯内存 |
| 集群部署配套 | K8s 滚动发布下长任务的收尾策略（踩坑点 #32）、连接池按实例数重算（踩坑点 #33）、工具幂等性边界声明（踩坑点 #34）、Skills 文件的多节点分发/一致性（单机版对账机制的分布式延伸，多节点部署改造相关笔记待补充细节） |
| 断点续传 / HITL 暂停恢复 | `PauseState` 快照（消息列表副本 + pendingToolCalls + SafePoint 阶段 + PauseReason）+ `PauseStateStore`（内存版 → JDBC/Redis）+ `resumeStream` 按原因分支恢复（HITL 审批通过→执行挂起工具；用户中断带新指令→跳过工具注入新消息）——**这是 Phase 3a"高危操作转人工审批"的前置机制**，审批必须能"暂停等人、之后恢复"（踩坑点 #61；框架侧机制已从参考源码读透） |
| 幂等工具模板 | 承接踩坑点 #34"框架不兜底幂等、责任在工具方"的边界声明，给写类工具一个标准实现模板：业务唯一键 upsert / 幂等 token / Outbox 三选一——不是每个工具各想各的 |

---

## Phase 2：SQL 数据分析能力包 —— ✅ 已完成（issue #52-#61）

> **执行口径**：Phase 2 不是把分析流程硬编码成固定 Workflow。先完成 Phase 2A 的身份/权限/Schema/SQL 安全基础，并用 ReAct + Skill 做隔离 Pilot；外层 Task/Checkpoint/Artifact 负责长任务和恢复。Phase 2B 再基于 Golden Tasks 决定是否扩大使用范围。Phase 3 的治理项中，SQL 安全、数据范围、脱敏和审计是本能力包的上线前置门禁。
>
> **2026-08-05 补充（对照 spring-ai-alibaba/DataAgent 的纠偏）**：DataAgent 不是纯 DAG，是"确定性骨架 + 局部自愈循环"——固定的 StateGraph 节点序列（IntentRecognition→…→SqlExecute）里，每个容易出错的节点后面都挂了条件重试 Gate（RelGate/SemGate/SQLGate2/PyGate2），报错就回退到对应生成节点重试，达到上限才降级/终止，Gate+maxRetries 天然是一个图结构给的重试上限。我们这边"SQL 报错自己修"靠的是 ReAct 循环里 LLM 临场决定，SKILL.md 的七步 SOP 是指导不是强制，**没有等价的图结构上限**——唯一的硬顶是 `AgentLoopExecutor` 的 `maxRounds`（analytics 执行器是 20，见 `AgentLoopExecutorFactory.java:191`），这是粗粒度的整轮上限，不是"同一个错误连续失败 N 次就提前止损"的电路断路器，20 轮里模型完全可能把"生成 SQL→报错→重新生成"这个子循环转上十几轮才撞顶，期间 token 成本已经烧出去了。这不是"Workflow 死板/ReAct 灵活"能一句话带过的差异，是 ReAct+Skill 路线一个结构性的开放风险，对应的补丁见下方"Analytics 重复失败熔断"（技术债清算清单）。

| 机制 | 说明 |
|---|---|
| M-Schema | JDBC `DatabaseMetaData` 内省 + 字符串字段异步采样示例值（带超时、敏感字段跳过采样）（踩坑点 #21） |
| SQL 安全校验 | JSqlParser 解析 + SELECT/WITH 白名单 + AST Visitor 危险函数检测 + 查询形状校验（JOIN 数量上限、OFFSET 必须配 ORDER BY）（踩坑点 #22） |
| 核心工具 | `listTables`/`describeTables`/`validateSql`/`executeSql`，六层防御 + SQLState 分类重试（踩坑点 #23/#24） |
| 业务术语消歧 | YAML 词典 + 精确匹配/同义词匹配（不用向量检索）+ 时间锚点元规则（踩坑点 #27/#28） |
| 复杂计算工具 | `calculate` 工具（表达式求值），明确不让 LLM 自己算、不让它用 Bash 绕过工具栈（踩坑点 #29） |
| **完整数据权限模型** | 按以下设计做：`sys_user`/`sys_role`/`sys_dept`（部门树物化路径）/`sys_user_role`/`sys_user_dept`，多角色取最宽权限；`DataScopeResolver` + `DataScopeRewriter` AST 改写 + `PermissionRuleRegistry` 差异化规则（踩坑点 #26/#35/#36/#37） |
| 敏感字段脱敏 | 按 `ResultSetMetaData` 真实列名/表名匹配防别名绕过，覆盖 executeSql 结果 + M-Schema 采样 + system prompt 用户画像（踩坑点 #25） |

---

## Phase 3：治理层（Hooks + 可观测性 + 评测体系）—— ✅ 已完成（issue #63-#71）

> **2026-07-31 修正**：本 Phase 原本的定位是"不属于参考框架自带的 19 项能力，是本项目
> 自己的增强"，这句话现在只对本表剩下的这几行成立——"审计日志"和"结构化输出校验"的**基础
> 机制**其实是参考框架自己的 core/17、core/13，已经挪到上面 Phase 0 状态表的 0.14/0.15。
> 这里两行改成明确"建立在 0.14/0.15 之上"的业务层增强，不是从零开始。
>
> **2026-08-06 更新**：9 张实现票（详细设计见 `docs/specs/backend-phase3-governance*.md`）全部
> 落地并关闭。落地时顺带把上一轮"下一步"记录的四条技术债（Redis 任务锁未接线、DeepResearch/PPT
> 无法取消、Analytics 无失败熔断、Golden Task 没跑过真实调用）一并清掉——这些原本是"进 Phase 3
> 之前要还的债"，实际落地时发现和 Hooks/PauseConfig/TraceStore 生产接线是同一批代码改动，拆开
> 反而要多接一次线，所以合并进了 Phase 3 的 ticket 里一起做。"结构化输出的业务审核层"（Reviewer
> Agent 二次确认）本次**不在范围内**——评测体系里的 LLM-as-Judge 承担了类似的"自动裁决"角色，
> 面向用户输出的二次审核留给有真实高风险输出场景（比如对外暴露的 SQL 建议）时再做，不是这次
> 为了凑"治理层"名头而硬做的功能。

| 机制 | 说明 | 状态 |
|---|---|---|
| Hooks 生命周期 | SessionStart/PreToolUse/PostToolUse/Budget/OnError/SessionEnd，六个拦截点 + `ToolRiskLevel`/`ToolRiskRegistry` 工具风险分级（`loop.hook` 包） | ✅ #63 |
| 权限分级 + 人工审批 | `PauseConfig` 首次接入生产装配（此前只有机制、从未真正启用），命中 `ToolRiskRegistry.toolsWithLevel(HIGH_RISK)` 的调用转人工确认；新增 `SessionBudgetTracker` 会话级 token 预算熔断 | ✅ #64 |
| 断点续传收尾 | DeepResearch/PPT 补上取消端点（`Future.cancel`/状态机级 `CANCELLED`）+ 运行中任务查询；顺带修了 `DeepResearchController.status()` 缺失的越权校验 | ✅ #65 |
| 审计日志防篡改 | **依赖 0.14 TraceAudit**——`TraceStore` 首次接入生产装配（同样此前只有机制从未启用），`JdbcTraceStore.save()` 内按 `conversationId` 算 SHA-256 哈希链（`SELECT ... FOR UPDATE` 保证并发安全），新增 `verifyChain()` 篡改检测 | ✅ #66 |
| 可观测性闭环 | Micrometer + OpenTelemetry：TTFT 和总耗时用独立 `Timer.Sample`（踩坑点 #38）、`ParentBased(TraceIdRatioBased(0.1))` 自定义 Sampler、Reactor 跨线程 Observation 传播（踩坑点 #39） | ✅ #67 |
| 部署 + SLO | 首个 `docker-compose.yml`（Prometheus/Grafana/Langfuse），三个 SLI（TTFT P95/端到端首响应/工具调用成功率）+ Grafana Alerting 阈值告警 | ✅ #68 |
| 成本治理 | `SessionBudgetTracker` 见上（#64）——按 conversationId 聚合 token 消耗，超限熔断，不是独立一票 | ✅ 并入 #64 |
| 评测体系 | `GoldenTaskRunner` 从 `src/test/java` 提升为生产可调用能力（此前不在生产 classpath 上，前端/后端都调不到）；新增真实样本筛选（强制人工确认，不自动信任生产流量）、LLM-as-Judge（含一致性方差校验）、Agent 指标（工具选择准确率/参数准确率/不必要调用率）、压缩 trade-off 实测 | ✅ #69 |
| 评测前端页面 | `EvaluationView.vue`：触发评测、轮询进度、按 dimension 看通过率、失败 case 详情、历史报告两两对比 | ✅ #70 |
| 评测用例可维护 + badcase 回流 | 反馈"页面只有一个跑按钮，用例改不了、badcase 也加不进去"——`golden_case` 表 + `GoldenCaseController` 增删改查用例（YAML 内建用例仍只读，二者合并后一起跑）；`GoldenCaseCandidateExtractor` 接线为真实 API（此前是孤立代码，没有 Controller 调它），新增 `GoldenCasesView.vue`/`GoldenCandidatesView.vue` 两个管理页；`EvaluationView.vue` 加了通过率趋势图 | ✅ 2026-08-06 |
| 结构化输出的业务审核层 | **不在这次范围内**——见上方 2026-08-06 更新说明 | ❌ 有意跳过 |
| 安全纵深（AI 特有） | Prompt Injection 检测（小模型分类）、工具调用速率限制（Redisson `RRateLimiter`，防 ReAct 死循环烧 token）、PII 打码（手机号/身份证号/银行卡号）、Bash 工具凭据隔离审查（发现并修复了真实的环境变量泄露） | ✅ #71 |
| A/B 测试 | 讨论过设计思路（模型/Prompt 版本分流 + 效果对比），本轮明确**不做**——见 `docs/specs/backend-phase3-governance.md` 的 Out of Scope | ❌ 有意跳过 |

---

## Phase 4：文件问答 + RAG 能力包 —— ✅ 已完成（issue #20/#21/#26/#27/#28）

| 机制 | 说明 |
|---|---|
| 文件解析 | 统一解析 PDF/Office/HTML/纯文本，走 Apache Tika（issue #21）——tika-core 2.9.2 自带的 commons-io 版本比它实际需要的旧，已在 pom.xml 显式钉到 2.18.0 |
| 小文件直出 / 大文件 RAG 路由 | 按字符数阈值（默认 5000）分流，避免所有文件都走 RAG 的延迟成本（issue #21） |
| RAG 检索管线 | 查询压缩（`CompressionQueryTransformer`）→ 多查询扩展（`MultiQueryExpander`，3 个改写+原始）→ PgVector 相似度检索（按 fileId 过滤）→ 去重合并（issue #26）；向量化失败直接抛异常，不像参考实现那样打个 warn 日志就静默降级 |
| 多轮文件生命周期 | `conversation_id`（跨轮可见性）vs `turn_id`（历史回放归属，指向 `agent_session.id`）两个独立 key；system prompt 里按"本轮上传"成组渲染，避免"这两个文件"被模型理解成单个列表项（issue #28，复用 issue #19 memoryStore 的注入缝） |
| 图片多模态 | 走 qwen3-vl-plus，懒加载（首次被问到才调用）+ 结果写回缓存，不对没人问起的图片烧多模态调用成本（issue #27） |

---

## Phase 5：联网搜索 + 复杂计算与图表生成

| 机制 | 说明 |
|---|---|
| Tavily MCP 联网搜索 | 条件工具，前端开关控制注入，关闭时零成本（工具列表里压根不存在，不是靠 prompt 让模型"别用"） |
| 图表生成 | mcp-echarts（streamable http，避免 stdio 并发串话）+ MinIO 图床，返回 URL 不返回 base64（避免上下文被图片数据打爆） |

---

## Phase 6：PPT 生成智能体

这块和 Phase 7 都在 Phase 0-5 之后，是核心 Agent 架构地基上的独立能力包，不阻塞前面任何东西。

| 机制 | 说明 |
|---|---|
| 技术路线选型 | 模板填充（设计与内容生成解耦），不是文生图/代码生成/HTML转PPT——四条路线评估后收敛的结果，各自的翻车点都要能讲清楚（踩坑点 #50） |
| 状态机 | `INIT→REQUIREMENT→SEARCH→TEMPLATE→OUTLINE→SCHEMA→RENDER→SUCCESS`，Strategy 模式一状态一实现 |
| 断点续传 | DB 行的 `status`/`errorMsg` 即 checkpoint，按**状态粒度**（不是子步骤粒度）恢复——这是一个可以讲清楚取舍的设计决策，不是"坑"（踩坑点 #44） |
| 意图识别 | CREATE/MODIFY/RESUME 三分支；继续/暂停判断走固定标记（【开始生成PPT】/【暂停生成PPT】），不做自然语言语义解析（踩坑点 #52） |
| Schema 生成 | `fontLimit` 只是模型的软约束，渲染侧必须暴力截断兜底，不能假设模型会严格遵守数值约束（踩坑点 #53） |
| AI 配图 | 调图片生成 API 拿到的 URL 有时效性，必须立刻下载转存自建对象存储（MinIO），不能直接持久化引用第三方临时链接（踩坑点 #54） |
| 渲染引擎 | **Python + python-pptx，不是 Java Apache POI**——POI 对 Group shape 支持差，而模板填充依赖 shape name 精确定位到最深层文本框，POI 处理不了会导致填充失败（踩坑点 #55）；Java 侧只管状态机流程编排，`ProcessBuilder` 起 Python 脚本传 Schema JSON |
| 素材生成（可选） | 官方 pptx skill 视觉效果单一（python-pptx 本身无绘图能力），可选自研 Pillow（命令式，零依赖但繁琐）或 SVG（声明式，画质更高但要装 cairo 系统依赖）两种图片素材生成增强（踩坑点 #56） |
| 信息收集阶段 | 保持 `scheduleRound → processChunk → finishRound` 的流式 Tool Calling 闭环：按 `toolCallId` 拼接参数，完整一轮后并行执行搜索并按原顺序回填；参数分片不直接展示给用户。由于 OpenAI 兼容客户端的省略 id 分片缺陷，带工具的 `qwen-plus` 请求路由到原生 `deepseek-chat`，普通对话继续走 SSE——依赖 Phase 5 的联网搜索能力 |

---

## Phase 7：深度研究 DeepResearch

| 机制 | 说明 |
|---|---|
| 需求澄清阶段 | 判断信息是否充分，不够则主动提问打断流程；继续/暂停判断同样走固定标记【需要补充信息】+ 关键词兜底，不做自然语言语义解析（踩坑点 #52，和 PPT 需求澄清是同一个坑同一套解法，能对比着讲说明这是可复用的工程模式而不是场景专属技巧） |
| 研究主题生成 | 基于澄清后的需求生成精确研究方向 |
| Plan-Execute-Critique 循环 | 按 `order` 分层调度（同层并发、跨层串行），`Semaphore(3)` 控制并发度，单跳依赖上下文（N 层只看 N-1 层结果，这是已知的简化设计，面试被追问"多跳依赖怎么办"时要能给出改造思路，踩坑点 #45） |
| 自我批判 | 每轮执行后评估信息充分性，决定继续迭代还是收尾 |
| 专用上下文压缩 | 比通用 `ContextCompactor` 更激进：超字符阈值直接整体摘要替换，只保留最新一条 Critique Feedback，历史 Critique 全部过滤丢弃避免累积膨胀（踩坑点 #46） |
| 综合报告生成 | 只用"已完成任务结果"块做输入，不用完整历史，保证最终报告言之有据 |
| 依赖 | 联网搜索能力（Phase 5），信息收集阶段的工具调用需要它 |

---

## Phase 8：多 Agent 编排

> **2026-07-31 修正**：下面"SubAgent 子代理机制"这一行本身**不需要等任何 Capability Pack**——
> 参考框架里这个机制是通用的（`SubAgentTool.create()` 包一个 `Supplier<ReactAgent>` 就能用，
> 官方示例挂的是"翻译""代码分析"这种不依赖业务包的简单子代理），已经在上面 Phase 0 状态表
> 标成 `8*` 单独说明。放在 Phase 8 这里，是因为"拿真实业务 Agent 演示多 Agent 协作"这件事
> 需要 Phase 2/6/7 至少存在两个，机制本身随时可以先做。

| 机制 | 说明 |
|---|---|
| Orchestrator + Specialist + Reviewer | 路由到不同专用 Agent（代码评审/慢查询诊断/发布检查），高风险输出必须过 Reviewer（结构化二元判定输出）才能对外展示（踩坑点 #42） |
| SubAgent 子代理机制 | 把一个 ReactAgent 包装成 `call_{name}` 工具挂到主 Agent 上；子 Agent 事件重新打 `SubAgentSource` 标记后转发到父流；**硬性禁止嵌套**（子代理不能再挂子代理/AskUser/暂停拦截，只允许一层）；ThreadLocal 跨接口边界传递上下文的取舍（踩坑点 #60）。DeepResearch 的另一条实现路线（ReAct+SubAgent 复刻）依赖这个机制 |
| Workflow Graph 执行器 | 自研轻量图执行器，支持顺序/条件分支/并行，选型标准是"执行路径能否提前确定"（踩坑点 #43） |

---

## Phase 9：MCP Server 化

| 机制 | 说明 |
|---|---|
| MCP Client（消费方向） | 接现成的 Git/文件系统/只读数据库 MCP Server，不用每个工具都重写连接逻辑 |
| MCP Server（暴露方向） | 把"SQL 数据分析""代码评审"封装成 MCP Server，让 Claude Code/Cursor/VS Code 等任意 MCP Host 直接接入复用——这是"我做了一个 Agent"升级成"我做了一个能被复用的能力"的关键差异点；对外暴露时目录白名单/参数化查询/Hook 审批是最低安全线，Server 初始化失败必须可观测（踩坑点 #40/#41） |
| 传输方式演进 | 本机开发用 stdio，团队共用/独立部署切 Streamable HTTP |

---

## Phase 10：框架迁移评估（ADR-0002 里明确留的口子）

不是"要不要用框架"的重新辩论，是"手写版本验证完、覆盖面稳定之后，有计划地评估"：

- 重新评估 AgentScope Java 2.0：Skill/Sandbox/Session 持久化/Scheduler/A2A 协议这些现成能力，是否已经到了"自己维护手写版本的成本 > 迁移成本"的临界点
- 或者只迁移编排层（用 Spring AI 2.0 的 `ToolCallingAdvisor`），保留自己的 Skills/ToolSearch/上下文压缩这些业务定制层不变
- 决策记录成新的 ADR，不管最后选哪个方向，这个"先手写、后评估框架"的完整演进过程本身就是面试叙事的一部分

---

## Phase 11：部署与运维

| 机制 | 说明 |
|---|---|
| 本地开发环境 | Docker Compose：app + MySQL(sakila) + Redis + PgVector（Phase 4 用到时再加） |
| CI 与测试体系 | 单测（0.0 的 ScriptedLlmClient 确定性测试 + 分片重组模糊测试）+ Testcontainers 集成测试（真实 MySQL/Redis，禁 H2）+ Sonar 扫描；Phase 9 之后补 MCP 契约测试（保证 schema 不破坏外部调用方）；混沌测试（Toxiproxy 注入 Redis 故障/网络分区，验证 #30-32 的降级路径真的生效而不是纸上谈兵） |
| 配置与密钥 | `application-local.yml` 是开发期方案；生产走配置中心（Nacos/Apollo + `@RefreshScope`）+ 密钥管理（Vault/KMS），API Key 不落明文 |
| 健康探针与优雅停机 | `/actuator/health` 分 liveness（只检 JVM）/readiness（检 Redis/MySQL/LLM 连通性）；K8s liveness 失败重启会丢内存会话——所以 Phase 1 的会话持久化是 K8s 部署的前置依赖；优雅停机保证进行中的 ReAct 轮次跑完或触发 PauseState 快照（踩坑点 #32） |
| 真正部署 | 视"部署好面试用"的具体要求决定——云主机/容器平台直接跑 Docker Compose，还是需要 K8s；镜像用 Buildpacks 分层（依赖层缓存，改代码只重建应用层） |
| 性能基线 | 单实例并发会话数目标（受 Redis 锁 + 连接池 + LLM 并发限制约束）、单会话平均 token、压缩触发后的延迟增量；Redis 锁续期频率随并发数线性放大，连接池要按并发数预算 |
| 前端演示页 | Vue3 + TS + Vite 单页应用，覆盖对话/文件问答/DeepResearch/PPT 生成——面试演示的视觉效果远强于 curl；详细设计见 [`docs/specs/frontend-v1.md`](specs/frontend-v1.md)，已拆票 [#38-#43](https://github.com/renjian-pro/AgentTrail/issues/38)；登录 + RBAC + 数据权限前端另见 [`docs/specs/frontend-phase2-auth.md`](specs/frontend-phase2-auth.md)（绑定下面 Phase 2 一起做，尚未拆票） |
| Demo 脚本 | 一套 curl/Postman 集作为前端之外的补充，覆盖每个能力包的典型场景 |
| 评测落地 | Phase 3c 的评测体系接上真实数据——data-agent 评测体系相关笔记，待补充具体指标口径 |

---

## 阶段间的依赖关系（谁先谁后有硬约束，谁先谁后没硬约束可以自己插队）

> **状态（2026-07-31 三次修正）**：issue #1-#19 全部关闭，Phase 0 + Phase 1 完整做完，和参考
> 框架 `docs/core/` 的 19 篇能力文档对齐（三处有意例外见上方状态速览的更新说明）。下面这条
> 硬约束现在真正清空——可以放心开始 Phase 2。

**核心排序原则**：Phase 0 + Phase 1 是"引擎"——通用、不含任何业务知识，对应参考框架
框架本身的能力集，**必须完整做完才能开始任何 Capability Pack**。这不是任意排的先后顺序，
是 `loop/` 分包原则（不知道 SQL、不知道 PPT）在构建顺序上的延伸：业务代码不应该在引擎接口
还没定型的时候就依赖上它。Phase 1 的 Redis 生产化本质是 Phase 0.4 任务管理能力的生产形态，
和 Phase 0 属于同一层，不是可以随意插队的独立项。

```
Phase 0（Runtime 核心）+ Phase 1（Redis 生产化）—— 引擎，必须先完整做完
  └─ 硬依赖：后面所有 Capability Pack 都要跑在这个 loop 上
       ├─ Phase 2（SQL 数据分析）── 独立 Capability Pack，无前置业务依赖，可第一个做
       ├─ Phase 4（文件问答 RAG）── 独立 Capability Pack
       ├─ Phase 5（联网搜索+图表）── 联网搜索无前置依赖；图表生成只是"要等 Phase 2 有数据可画"（运行时数据流关系，不是构建顺序约束）
       ├─ Phase 6（PPT 生成）── 依赖 Phase 5 的联网搜索（收集素材阶段用）
       └─ Phase 7（DeepResearch）── 依赖 Phase 5 的联网搜索
Phase 3（治理层）—— 不属于参考框架自带的 19 项能力，是本项目自己的增强；Phase 0.9 的内置
  工具（Bash/FileSystem/Grep）本身就是"真实工具调用"，Phase 0 做完即可开始 Phase 3，不需要等
  任何 Capability Pack
Phase 8（多 Agent 编排）—— 依赖至少 2-3 个 Capability Pack 已存在（Phase 2/6/7 至少两个），否则没有"协作"的意义
Phase 9（MCP Server 化）—— 依赖至少一个 Capability Pack 成熟到可以对外暴露
Phase 10（框架迁移评估）—— 依赖 Phase 0 手写版本已经稳定运行过一段时间，不是一开始就做
Phase 11（部署）—— 每个 Capability Pack 做完都可以顺手补一版 Demo，不用等全部做完
```

## 参考来源映射（每个阶段的设计从哪来，面试被问"这个设计怎么来的"时有据可查）

| 阶段 | 来源 |
|---|---|
| Phase 0.1-0.4 loop 核心 | 研读真实生产级 Java Agent 框架源码（`AgentLoopExecutor`/`ToolCallExecutor`/`ContextCompactor` 等核心机制）+ 系统整理的流式响应、任务管理、Think 模型输出解析、上下文压缩（micro/auto_compact）相关工程笔记 |
| Phase 0.6 ToolSearch | 研读参考框架的工具检索模块（jieba 中文分词 + HYBRID 兜底）+ 按需工具披露机制相关工程笔记 |
| Phase 0.7 Skills | 研读参考框架的 Skills 工具实现 + Skills 机制设计、单机版管理相关工程笔记 |
| Phase 0.9 文件/Bash 工具 | 研读参考框架的内置工具实现（文件系统/Bash/Grep）+ Agent 操作系统能力相关工程笔记 |
| Phase 1 分布式任务管理 | 多实例 Agent 任务管理改造相关工程笔记；分布式锁的注解化实现对照了同类开源方案的 `@DistributeLock` 模式（`@Order(MIN_VALUE)` 保证锁在事务外，含真实缺陷可当反例，踩坑点 #65） |
| Phase 2 SQL/权限 | M-Schema、SQL 安全校验、执行流程、权限模型改造、数据权限计算与改写、敏感字段脱敏、业务术语消歧相关工程笔记 + 数据分析类 Agent 项目的工具设计模式 |
| Phase 3 治理层 | 研发效能 Agent 平台的 Hooks 设计笔记 + 参考框架的 `TraceManager` 实现 |
| Phase 4 文件问答 RAG | 文件问答实现、大文件处理、文件与联网搜索重构相关工程笔记 |
| Phase 5 搜索+图表 | 复杂计算与图表生成相关工程笔记 |
| Phase 6 PPT | PPT 生成智能体系列工程笔记（选型/需求分析/稳定输出/Python渲染/失败恢复/重写Skill） |
| Phase 7 DeepResearch | DeepResearch 实现、智能体自主规划相关工程笔记 |
| Phase 8 多 Agent | 研读参考框架的 SubAgent 工具化实现；对照另一种常见实现——注意实测口径：它是 SubAgent-as-Tool 架构（LLM 串行决定调哪个子 Agent，**没有**并行 fan-out/结果合并），实际挂载 4 个子 Agent；真正值得抄的是三层意图路由（规则→向量→LLM）+ 影子历史补偿（#70）+ Tool 粒度手写熔断（#71）+ 注册表集群教训（#69） |
| Phase 9 MCP | 研发效能 Agent 平台的 MCP 设计笔记 + 相关工程笔记 |
| 待补充 | 中断恢复实现、多节点部署改造、data-agent 评测体系相关笔记——已列入整理清单，后续补充对应阶段细节 |

## 面试叙事主线

详细版见 **`interview-narrative.md`**（三条叙事主线：并发与线程 / Spring 生态 / 数据与一致性，每条按"项目坑→解法→八股锚点"三段式组织，外加 demo vs 工程落地对照清单）。骨架：

1. V0（AgentScope Java 2.0）→ V1（手写 loop）的决策演进，见 ADR 0001→0002。
2. Loop 内部机制的具体细节（工具调用分片重组、上下文压缩、Thinking 分流）。
3. 每个 Capability Pack 的企业级细节都有真实工程理由（SQL 安全/权限模型/脱敏/Redis 分布式任务管理），不是功能堆砌。
4. Phase 10 的"先手写证明理解，再有计划引入框架"本身是一条完整的架构决策线，能完整讲述从 0 到 N 的演进过程，而不是只有一个静态的最终状态。

## 下一步

**2026-08-06 更新**：issue #1-#19（Phase 0+1）、issue #52-#61（Phase 2 SQL 数据分析能力包）、
issue #63-#71（Phase 3 治理层）全部关闭。上一版"下一步"记录的四条技术债——`AgentTaskManager`
未接 Redis 锁、DeepResearch/PPT 无法取消、Analytics 无失败熔断、Golden Task 没跑过真实调用——
已经全部还清，具体见上方 Phase 3 表格的"2026-08-06 更新"说明（这四条实际上是和 Phase 3 的
Hooks/PauseConfig/TraceStore 生产接线同一批改动，合并做掉了，不是单独还债）。

**引擎 + 全部规划中的 Capability Pack + 治理层现在都已经落地**，剩下的是尚未开工的方向（按
上面的阶段划分，没有硬性先后顺序约束，可按面试准备节奏或兴趣挑）：

- Phase 8（多 Agent 编排）——`SubAgent` 机制本身随时可做，"拿真实业务 Agent 演示协作"需要至少
  两个 Capability Pack（Phase 2/6/7 已经都有了，条件已满足）
- Phase 9（MCP Server 化）——把 SQL 分析/代码评审封装成 MCP Server 对外暴露
- Phase 10（框架迁移评估）——手写版本已经稳定跑过 Phase 0-3 全部阶段，可以着手评估 AgentScope
  Java 2.0 迁移的成本/收益，产出一份新 ADR
- Phase 11（部署与运维）——`docker-compose.yml`（#68）已经把 Prometheus/Grafana/Langfuse 起来了，
  真正的 K8s 部署、CI 完整化、性能基线仍是空白

已知的、故意留到后续的缺口（不阻塞 Phase 2，但动到对应机制时要记得补上）：
- ~~`AgentTaskManager` 不会定时续期已持有的 Redis 锁~~ → 已补上：`RedisTaskLock.startAutoRenewal()`
  显式开启后台续期（每 `ttl/3` 续一次），默认不开启，行为和之前完全一致；`RedisTaskLockIT` 覆盖了
  "续期让锁活得比原始 TTL 久""没开启时按原 TTL 正常过期""一批锁里有一个丢了归属不影响其它续期"
  三种场景
- ~~`PauseStateStore`/`TraceStore`/`MemoryStore` 只有内存实现~~ → 已补上 JDBC 实现
  （`JdbcPauseStateStore`/`JdbcTraceStore`/`JdbcMemoryStore`），表结构在 `db/schema.sql`
  （`agent_pause_state`/`agent_trace`/`agent_memory`），走真实 MySQL 集成测试。`PauseState` 整体
  序列化成一段 JSON（`PauseStateJson`，含消息历史、挂起工具调用、`RunnableParams`——`OutputType`
  按目标类的类名存，恢复时 `Class.forName` 重建，前提是该类还在 classpath 上）；`TraceStore`/
  `MemoryStore` 因为本身是扁平记录，按列存不用整体 JSON
- DeepSeek `reasoning_content` 的流式行为还没拿真实 key 实测过（`DeepSeekLlmClientLiveIT` 已经
  搭好，缺一次真实调用去跑它）
- V1 的 HTTP 入口（`AgentLoopExecutorConfig` + `AgentLoopController`，`POST /agent/v1/chat`）目前
  已接入会话持久化、联网搜索和图表工具，并通过 `stream()` 返回 SSE；暂停恢复、追踪审计、分层记忆
  等机制仍按场景扩展。V0 的旧入口（`AgentController`，`/agent/chat`）保留不动，两者互不影响
- `GoldenEvaluationService.executeCase()`（`/agent/v1/evaluation/run` 实际跑的路径）从没计算过
  `rowCount`/`scalar.*`/`resultMatchesReference` 这几个 metrics，只有 `GoldenTaskLiveIT` 测试代码里的
  `populateMetrics()` 真的算了。用到 `result_matches_reference`/`row_count_*`/`scalar_*` 断言的用例
  （含内建的 `sql-001`/`sql-003`）在页面上跑永远失败，不是这次"用例可维护"改动引入的新问题，是
  2026-08-06 顺手查出来的既有缺口，见踩坑点 #91；把 `populateMetrics` 那套"重跑 referenceSql 并按行
  对比"的逻辑搬进生产服务是后续单独一票
- 2026-08-06 因为一次真实的全站卡死事故，连续补了三道模型/工具调用的超时防线（踩坑点 #92/#93）：
  `SynchronousLlmCall`（6 处同步小模型调用统一超时出口）、`ToolCallExecutor` 的工具轮次超时、
  `AgentLoopExecutor` 的整轮绝对时钟兜底。`legacy/V0.java` 的 `agent.call(...).block()` 是审计出来
  但**没有**一起修的同一类问题——V0 触达面小（前端只走 V1），且要不要继续维护 V0 是另一个独立决定，
  见踩坑点 #92 的审计结论
