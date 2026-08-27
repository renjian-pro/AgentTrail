# Ticket 20（Phase 9）：多 Agent / Skills / MCP / A2A — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by
> [Ticket 17](refactor-remediation-ticket-17.md)、[Ticket 18](refactor-remediation-ticket-18.md)
> （需要至少一个真实 Workflow 案例作为多 Agent 编排的落地对象，不能在真空里设计）。

## 0. 范围边界

**这一票是探索性设计，不是修复现有代码**——`refactor-blueprint.md` §2.6 已经确认"加一个全新
业务能力没有统一扩展点"是当前真实缺口，但"多 Agent 编排"这件事在当前代码库里基本是空白：
没有 `AgentDefinition`、没有 `AgentRegistry`、没有子 Agent 调用机制、A2A 协议完全没有实现。
和 Ticket 11-19 那种"先读现状代码、验证假设、再决定改不改"的工作方式不同，这张票没有太多现状
可读——**实现过程中允许调整接口设计**，票面上给的类型签名是设计意图的表达，不是必须逐字实现
的契约；如果实现时发现更好的形状，以实现时的判断为准，不需要回来改这张票的文字。

**唯一的例外是 Skills 系统**（§0.5/目标⑤）：这部分不是空白，`loop/skills` 包已经有一套运行良好
的插件化机制，本票动笔前实际读过 `Skill.java`/`SkillMetadata.java`/`SkillReconciliation.java`
三个类，确认了两件事——

1. `SkillMetadata`（数据库落地的一半）目前只有 `name`/`skillPath`/`description`/`enabled` 四个
   字段，确实没有 version/权限/资源相关的元数据，目标⑤要加的字段现状里是空的，不是重复劳动。
2. `Skill.toXml()`（[Skill.java:71-77](../../src/main/java/com/agenttrail/loop/skills/Skill.java)）
   的注释**明确记录了一个刻意的设计决定**：只渲染 `name`+`description` 两个字段进模型看到的
   工具描述，不把 `version`/`license`/`allowed-tools` 这类"和该不该选这个技能无关的键"一起倒
   出去，理由是"渐进式披露的前提是摘要要足够小，会往每次请求的提示词前缀里灌噪音"。**这一条
   对目标⑤有直接约束**：新增的 version/权限/资源元数据只能存进 `SkillMetadata`/新的
   `SkillPack` 结构里供注册/装配/审计用，绝不能顺手加进 `Skill.toXml()` 渲染给模型的那段摘要，
   否则就是在重新引入这个类已经刻意避开过的噪音问题——这不是本票要重新设计的地方，是要respect
   的既有决定。

## 1. `AgentDefinition`

一个 Agent 是什么由一份声明式定义描述，不是一段硬编码的调用代码：

```java
public record AgentDefinition(
        String id,
        String description,          // 供路由决策使用，见目标②
        AgentProfile profile,        // 复用 Ticket 14 的 RuntimeProfile 概念：模型选择、
                                      // ContextCompaction/Memory/PauseResume 等可选模块的显式声明
        List<ToolDefinition> tools,  // 复用 Ticket 13 的 ToolDefinition（见目标⑥）
        InputContract input,
        OutputContract output,
        AgentPolicy policy           // 预算、超时、允许的最大嵌套深度（见目标③）
) {}
```

`AgentProfile`/`InputContract`/`OutputContract`/`AgentPolicy` 的具体字段本票不预先定死——
`AgentProfile` 应该尽量复用 Ticket 14 已经定义的 `RuntimeProfile`/`RuntimeModule` 概念而不是
另起一套可选项表达方式（Ticket 14 写作时如果还没落地，以其最终形态为准）。

## 2. `AgentRegistry` + `AgentRouter`

`AgentRegistry` 是 `AgentDefinition` 的注册表，启动时装配（可以是 `@Bean` 列表，也可以像
`SkillReconciliation` 那样做成目录扫描——两种都合理，实现时按当时的部署形态选，这不是本票要
提前锁死的决定）。

`AgentRouter` **规则路由优先，模型路由兜底**，给一个具体的路由决策示例说明这个优先级怎么工作：

> 用户发一句"帮我把这份财报生成一份 PPT"。`AgentRouter` 先看有没有 Agent 在其 `AgentDefinition`
> 里声明了能命中这句话的关键词/意图规则（比如 PPT 生成 Agent 声明了 `keywords: ["生成PPT",
> "做一份演示文稿", "PPT"]` 或一条更结构化的意图匹配规则）——命中就直接路由过去，不调用任何
> 模型，是纯规则匹配，延迟最低、成本为零、行为可预测。如果没有任何 Agent 的规则命中（比如用户
> 问的是一句意图不明显的话），才退化到**一次轻量模型调用**：把用户消息 + 所有已注册 Agent 的
> `description` 列表丢给一个便宜的小模型（模式对齐 `PromptInjectionGuard` 已经验证过的"单次
> 同步小模型分类调用"，不新发明一套调用方式，参考 `../phase3-governance/backend-phase3-governance-ticket-09.md` 第
> 1 节对 `MemoryExtractor`/`PromptInjectionGuard` 这个模式的复用先例），让它从候选列表里选一个
> 最匹配的 Agent id 或返回"没有合适的 Agent"。

规则路由优先的理由：多 Agent 场景下大部分真实请求的意图是清楚的（用户会直接说"生成 PPT"/
"帮我搜索资料"这类明确指令），为这部分请求单开一次模型调用纯属浪费；模型路由只需要覆盖规则
覆盖不到的长尾。

## 3. `SubAgentRunner`

限制嵌套深度、预算和权限，防止一个 Agent 调用另一个 Agent、那个 Agent 又调用第三个 Agent，
无限递归下去。

**默认嵌套深度上限建议值：3 层**。理由：真实的多 Agent 协作场景（比如 Supervisor 分发给若干
执行 Agent，执行 Agent 内部再调用一次专门的验证/格式化 Agent）三层足够表达；超过三层通常意味着
职责划分有问题——应该拆成更清晰的阶段/状态（沿用 Ticket 15/17 的 Task/Checkpoint 模式，不是复用
某个通用 Workflow 引擎，见第 4 节），而不是
用嵌套 Agent 调用去表达复杂编排逻辑，嵌套越深，失败时的可观测性和预算追踪越难做对。这个数字
不是压测校准值，是"面试时能讲清楚为什么是这个数"的经验性上限，实现时如果有更具体的业务场景
证明需要更深，允许调整，但要求调用方明确说明为什么三层不够。

`SubAgentRunner` 的职责：

- **嵌套深度校验**：每次子 Agent 调用把当前深度 +1 传下去，超过上限直接拒绝并返回明确错误，
  不是让调用栈自然爆掉。
- **预算传递与校验**：子 Agent 消耗的 token/工具调用次数要能汇总回父 Agent 的预算（复用 Ticket
  2/9 已有的 Budget Hook 熔断机制，不新造一套预算模型），子 Agent 不能绕开父级预算上限。
- **权限收窄**：子 Agent 拿到的工具权限只能是父 Agent 权限的子集，不能通过嵌套调用升权。

## 4. `SupervisorWorkflow`

**2026-08-11 已定案：不存在通用 Workflow 引擎，`SupervisorWorkflow` 是一个具体类**（本节原来假设
`refactor-blueprint.md` §2.8 会提供一个 `WorkflowDefinition<S>` 接口，这个接口已经废弃，见
`refactor-remediation-ticket-17.md` §0）。本票 Blocked by Ticket 17/18 的原因改成：**沿用 Ticket
17 落地后 `DeepResearchWorkflow` 的具体写法**（`DeepResearchStage` 枚举 + 显式驱动方法 + 复用 Ticket 15 的
`TaskCoordinator`/`CheckpointStore`），不是"复用同一个引擎实例"，是"复用同一套已经验证过的实现
模式"——`SupervisorWorkflow` 自己定义一个 `SupervisorStage` 枚举（`FAN_OUT → MERGE_RESULTS →
REVIEW（可选，决定要不要打回重新 fan-out）→ FINALIZE_ARTIFACT`）和自己的具体驱动方法，不 `implements`
任何跨能力包的通用接口。

这个阶段序列和 DeepResearch 的 `FanOut(SearchTask[]) → MergeEvidence → Critique（pass→Synthesize /
fail→Plan 带反馈）` 是同一种"fan-out 后合并再复核"的结构模式，只是节点内部执行的是子 Agent 调用
而不是搜索任务——这是两个能力包各自代码长得像、不是共用了同一段代码或同一个接口。如果
`SupervisorWorkflow` 需要"子 Agent 的动态数量""reviewer 打回后只重跑失败的分支而不是整个
fan-out"这类 DeepResearch 没遇到过的新需求，直接在 `SupervisorWorkflow` 自己的驱动方法里写，不需要
反馈给任何共享引擎——具体类之间没有需要"协调抽象"的耦合关系。

## 5. Skills 改造成可注册的 `SkillPack`

**不重新设计 `SkillReconciliation` 扫描目录自动注册的机制**——这一条已经是目前做得最好的
插件化例子（加新技能 0 个 Java 文件），本票只在现有 `Skill`/`SkillMetadata` 结构上**扩展**
元数据，不推倒重来：

```java
public record SkillMetadata(
        String name, String skillPath, String description, boolean enabled,
        String version,              // 新增：来自 SKILL.md frontmatter 的 version 字段
        SkillPermission permission,  // 新增：这个技能允许调用的工具/资源范围
        SkillResourceLimits limits   // 新增：预算/超时一类的资源约束
) {}
```

`SKILL.md` 的 frontmatter 解析（`SkillDocument.parse`，`Skill.load` 已经在用）本身不用改，只是
多读几个键；`SkillReconciliation` 的对账逻辑（发现新技能/清理孤儿/刷新描述）不用改，`enabled`
这类"数据库独有、磁盘刷新时不能被覆盖"的字段处理方式，新增的 version/permission/limits 字段
按"每次对账从磁盘刷新"还是"和 enabled 一样是 DB 独有状态"要分别判断——**version 应该跟着磁盘
刷新**（SKILL.md 改了版本号，下次对账就该更新），**permission/limits 更像运营在 DB 里手动收紧
的管控项**，不应该被磁盘内容覆盖，这条设计取舍要在实现时明确写进代码注释，参照
`SkillMetadata` 现有 javadoc 说清楚"文件系统 vs 数据库谁赢"这套已有约定的写法。

**再次强调 §0 提到的约束**：`Skill.toXml()` 渲染给模型的摘要只能保留 `name`+`description`，
新增的 version/permission/limits 元数据用于注册、装配时的权限校验、运营审计，不进入模型看到的
工具描述文本。

## 6. MCP 只作为 Tool Adapter

呼应 Ticket 13 的 `ToolGateway` 设计（`refactor-blueprint.md` §1.8：工具层拆成
`ToolDefinition`/`ToolResolver`/`ToolExecutor`/`ToolResultPolicy` 四个接口）。当前代码库里 MCP
的使用方式（`ChartToolProvider` 用 `SyncMcpToolCallbackProvider` 把 MCP 工具转成普通
`ToolCallback`，[refactor-blueprint.md §2.7](../../refactor-blueprint.md) 已确认这条路径和普通工具
走完全一样的 `ToolStart`/`ToolEnd` 事件，没有降级）已经符合"MCP 是工具的一种实现，不是特殊
路径"这个方向。本票要确认的是：这个边界在多 Agent 场景下依然成立——`AgentDefinition.tools`
里出现的 MCP 工具，对 `AgentRegistry`/`AgentRouter`/`SubAgentRunner` 来说应该和任何其它
`ToolExecutor` 实现没有区别，多 Agent 编排层不应该出现任何 MCP 专属的类型判断或特殊分支。如果
Ticket 13 落地的 `ToolExecutor` 抽象还没最终定型，这条在本票里先作为设计原则记录，具体校验
留到 Ticket 13 完成后再补一轮回归确认。

## 7. A2A 只暴露稳定的 Agent Card/Task/Event/Artifact 协议

A2A（Agent-to-Agent）协议这一票只做协议面的骨架设计，不做完整互操作实现：

- **Agent Card**：描述一个 Agent 能力的公开元数据（对外版的 `AgentDefinition` 摘要，去掉内部
  实现细节，类比 `Skill.toXml()` 只暴露 name/description 而不暴露内部结构的思路）。
- **Task**：外部发起的一次 Agent 调用请求，复用 Ticket 15 统一的 Task 模型，不为 A2A 单独建一套
  任务语义。
- **Event**：Task 执行过程中的进度/结果事件，复用 Ticket 15 的 `EventEnvelope`。
- **Artifact**：Task 产出的具体成果引用，复用 `refactor-blueprint.md` §2.8 已经定义的
  ArtifactStore/ArtifactId 概念（PPT/DeepResearch 报告已经在用同一套产物语义）。

## 分阶段交付建议

这一票允许分阶段交付：

- **第一阶段（必须在本票范围内完成）**：①`AgentDefinition` ②`AgentRegistry`+`AgentRouter`
  ③`SubAgentRunner`——这三项是后续一切多 Agent 能力的地基，且互相之间耦合紧，拆开交付意义
  不大。
- **第二阶段（可以拆成后续子票）**：④`SupervisorWorkflow`（依赖 Ticket 17/18 提供的真实
  Workflow 案例趋于稳定后再做，避免两边同时变动）、⑤Skills 元数据扩展（相对独立，风险低，
  随时可以单独排期）、⑥MCP 边界确认（依赖 Ticket 13 落地节奏）、⑦A2A 协议骨架（对外协议，
  优先级最低，建议放最后）。拆分子票时延用本票的编号加字母后缀（比如 Ticket 20a/20b），
  Header 各自 `Blocked by` 指向本票和它们各自依赖的前置票。

## Testing Decisions

- **路由决策**：覆盖"规则命中"（构造一个声明了明确关键词规则的 Agent，验证匹配请求直接路由过去
  且不产生任何模型调用）和"模型兜底"（构造一个规则规则都不命中的请求，验证走了轻量模型分类调用
  并按分类结果路由，分类结果为"没有合适 Agent"时返回明确的无法处理提示而不是随便选一个）两条
  路径。
- **`SubAgentRunner` 保护性测试**：嵌套深度超限（构造一条超过 3 层的调用链，验证在第 4 层被拒绝
  而不是自然递归到栈溢出）；预算超限（子 Agent 消耗的预算加总超过父级上限，验证被熔断而不是
  继续执行）。
- Skills 元数据扩展：version 字段跟随磁盘对账刷新、permission/limits 字段不被磁盘覆盖——这两条
  分别验证 §0.5 提到的"谁赢"约定没有被实现反过来；`Skill.toXml()` 输出快照测试确认新增字段确实
  没有出现在渲染给模型的文本里。

## Out of Scope

- 这一票不要求立刻有具体业务场景使用多 Agent 编排——是能力建设，不是紧急需求，交付节奏可以比
  Ticket 11-19 更宽松，不需要为了赶速度牺牲设计质量。
- A2A 协议与外部第三方 Agent 平台的真实互操作验证（这一票只定义协议骨架，不做联调）。
- `SupervisorWorkflow` 的 reviewer 节点具体打分/复核策略调优——这一票只要求节点角色划分清楚，
  不要求复核逻辑本身达到生产可用的准确率。
- MCP 工具的权限模型细节（哪些 MCP server 可信、要不要签名校验）——这属于 Ticket 13
  `ToolGateway` 的范围，本票只确认多 Agent 场景下 MCP 依然只是 `ToolExecutor` 的一种实现，不
  重新设计 MCP 本身的信任边界。
