# Clippings 与 AgentTrail 交叉核对（2026-08-06 增量）

> 承接 [`dodoagentx-crosscheck-2026-08-03.md`](dodoagentx-crosscheck-2026-08-03.md)（下称"上一版"）。上一版覆盖的是 SQL 数据分析领域（Schema/权限/脱敏/SQL 安全）和登录体系，本次核对 Clippings 在 2026-08-05～08-06 新增的一批文档（意图识别三层架构全家桶 10 篇 + `✅dodo Agent 面试通关指南` + `✅讲讲你的dodo项目遇到哪些技术难点` + `✅AgentScope Java特性：长期记忆`），逐条对照当前 AgentTrail 代码（`grep` 验证，不是只读文档），找真实缺口。

## 结论

最大的一个缺口不在某个 Capability Pack 内部，而是 **Runtime 层完全没有"意图识别 + 多智能体编排路由"这一整套机制**——这恰好是 `dodo Agent 面试通关指南` 里最高频的面试题之一（"如果只能有一个入口怎么处理？"），目前 AgentTrail 对这个问题唯一的答案是"前端按 UI 入口分别调用不同 controller"，追问一句"后端怎么统一路由"就没有代码可以支撑。除此之外还核实出三处"机制留了口子、但没有具体实现"的缺口（引用来源、推荐问题、长期记忆的语义召回），以及一处已经做对、比 Clippings 描述的坑更早规避掉的正面结果（并发工具调用的结果排序）。

---

## 一、新发现的重大缺口：意图识别三层架构 + MasterAgent/SubAgent 编排

Clippings 新增的 10 篇文档完整描述了一套生产级方案（对照代码：`IntentRecognitionResult`/`IntentCategory`/`IntentRecognitionRouter`/`IntentRecognitionAgent`）：

- **L1 规则/关键词**（纯内存正则，<50ms，不调模型）
- **L2 向量相似度**（RAG embedding 检索，<100ms，只做 embedding 不调 LLM）
- **L3 LLM 兜底**（单轮、无工具、`maxIters=1`，不套 ReActAgent；输出严格 JSON）
- 三层输出**同构**到统一的 `IntentRecognitionResult`（`intents`/`primary_intent`/`multi_intent`/`overall_reason`），下游调度不关心是哪一层命中的
- 每个意图自带 `defaultTargetAgent`（Spring bean 名），16 个意图收敛到 5 个 SubAgent，路由层不用单独维护映射表
- **问题改写不是必经步骤**：先拿原始问题跑 L1/L2，命中就跳过 `QueryRewritingAgent`；未命中才改写后重新走一遍 L1/L2（改写后大概率能命中，所以要再走一次而不是直接进 L3）
- **单意图快车道**：高置信度 + 白名单 → 直接跳过 MasterAgent，直连 SubAgent
- **多意图强制走 MasterAgent**：`multi_intent=true` 时不能任何一个 SubAgent 独立处理，MasterAgent 把每个 SubAgent 当工具，在自己的 ReAct 循环里按 `intents` 数组的依赖顺序依次调度，"不要先询问用户同意"

**AgentTrail 现状**：`grep -rl "意图\|Intent\|intent"` 全仓库命中的只有 `PptIntentRecognizer`（PPT 场景内部的 CREATE/MODIFY/RESUME 三分支固定标记判断，参见 `PptIntentRecognizer.java`）和 DeepResearch 的固定标记判断——两处都是**单一 Capability Pack 内部**的意图识别，不是 Runtime 级的跨能力包路由机制。`roadmap.md` 只在 Phase 8 的参考来源映射里有一行脚注提到"真正值得抄的是三层意图路由（规则→向量→LLM）"，从未落成 Phase 表格里的一项、一张 ticket 或一份 spec。

**为什么这是个真缺口而不是"暂时不需要"**：

1. AgentTrail 现在确实是"前端按入口分流"（Chat/PPT/DeepResearch/Analytics/RAG 各自独立 controller），和 dodo 的"多个前端入口"形态一致；但 `interview-narrative.md`/`roadmap.md` 的定位是覆盖面试高频问题，而"只有一个入口怎么办"是这套体系里**证明"是否真的理解多 Agent 架构"的分水岭问题**——只讲前端分流会被面试官追一句"那如果只能给一个统一入口呢"，现在答不上来。
2. 机制本身不依赖任何新 Capability Pack：AgentTrail 已经有 ≥2 个可路由目标（Analytics/DeepResearch/PPT/RAG Chat），比 dodo 示例里 5 个 SubAgent 的前置条件还宽松，随时能做，不用等其它 Phase。
3 . Phase 8 现有条目（`SubAgent` 工具化包装、`Orchestrator+Specialist+Reviewer`）解决的是"一个 Agent 内部调子 Agent"，和"统一入口的意图路由分发"是两个不同层次的问题——后者更接近"该不该进 Agent 循环之前先分流"，前者是"进了循环之后怎么调用别的 Agent"。两者都需要，不能互相替代。

**建议**：在 `roadmap.md` 里补一个明确条目（可以并入 Phase 8，也可以单开 Phase 2C——因为它是 Runtime 级机制、无 Capability Pack 前置依赖），至少包含：
- 统一入口 + L1 规则 / L2 向量 / L3 LLM 兜底的三层短路管道，产出同构 JSON
- 问题改写 Agent（仅在 L1/L2 miss 时触发，改写后重新过一遍 L1/L2）
- 单意图高置信度直连 SubAgent 的快车道 + 多意图强制走 MasterAgent 编排（依赖顺序执行、不二次确认）的双分支路由

---

## 二、面试指南（Q&A）暴露的具体业务点核对

逐条对照 `✅dodo Agent 面试通关指南` 提到的实现细节和当前代码：

| # | 面试指南描述的能力 | AgentTrail 现状 | 结论 |
|---|---|---|---|
| Q5 | 引用溯源：`BaseAgent` 维护 `allReferences` 列表，流式收尾前单独发一条 `type=reference` 消息 | `StageOutputProvider` SPI 存在（`AgentLoopExecutor.java:1033` 注释明写"留给 provider 一次机会插引用链接/推荐问题这类收尾输出"），但全仓库没有任何具体 Provider 实现引用来源 | ❌ 缺口：机制留了口子，没人接 |
| Q6 | 推荐问题：单独调一次模型生成 3 个问题，且必须显式注入"当前可用工具名称+能力边界"防止生成超出能力范围的问题 | 同上，`StageOutputProvider` 无任何实现 | ❌ 缺口：同一个口子 |
| Q7 | 每轮工具调用结束后手动插入一条引导 `UserMessage`（"已完成一轮执行，需要判断是否输出最终结果还是继续调用工具"），防止弱工具调用模型过早收尾 | `grep` 未命中任何类似的每轮强制反思注入；AgentTrail 现有机制是 `maxRounds` 强制收尾（触底才处理）+ SKILL.md 的 SOP 指导，没有"每一轮都插一条判断消息"这种主动纠偏 | ⚠️ 待确认是有意的设计取舍还是遗漏——如果对接的模型工具调用能力偏弱（比如私有化部署模型），这条防线目前是空的 |
| Q10 | MCP 工具过多的 5 种优化：工具分组/`toolFilter`、描述精简、动态加载（对标 Claude Code ToolSearch）、工具索引（RAG 检索工具）、上下文压缩 | AgentTrail 已有 Phase 0.6 ToolSearch（HYBRID：关键词优先，零命中才 fallback LLM），覆盖"动态加载"和部分"工具索引"；`toolFilter` 式的按 MCP 端点分组过滤未见实现 | ✅ 核心思路已覆盖，`toolFilter` 分组是可选补充项 |
| Q11 | 并发工具调用必须按原始 `tool_call` 顺序回填结果，否则模型侧顺序错位关联导致推理链错乱 | `ToolCallExecutor.execute()` 已用 `flatMapSequential`（而非 `flatMap`）保证并发执行但按源顺序回填，代码注释精确复述了这个坑（"OpenAI 形状的协议要求 tool 响应与 tool_call 一一对应，顺序错了模型侧会错位关联"） | ✅ 已正确实现，且早于本次核对就有意识地规避了这个坑 |
| Q13 | 大文件全局问题（"这个故事的主题是什么"）：分层摘要（chunk→摘要 chunk→更高层摘要）或 GraphRAG/LightRAG，区分全局/局部问题走不同检索路径 | Phase 4 RAG 管线（`RagRetrievalService`）是查询压缩→多查询扩展→PgVector 相似度检索→去重，纯 chunk 级局部检索，没有分层摘要 chunk，没有全局/局部路径区分，没有 GraphRAG 选项 | ❌ 缺口：全局性问题（"这篇文档讲了什么"）目前只能拼凑局部检索结果，容易答不全 |

---

## 三、长期记忆：AgentScope 方案 vs AgentTrail 现状

`✅AgentScope Java特性：长期记忆` 描述的 `LongTermMemory` 接口（`record`/`retrieve`）有三种工作模式：

- `STATIC_CONTROL`：每轮自动检索注入 + 异步后台 `record`（不依赖模型工具调用能力）
- `AGENT_CONTROL`：注册 `recordToMemory`/`retrieveFromMemory` 两个工具，模型自主决定何时记忆/回忆（token 消耗更低、精确率更高）
- `BOTH`（推荐）：两者叠加
- 检索统一是**向量相似度 Top-K**，不是全量拼接

AgentTrail 的 `loop/memory` 包（Phase 0.12，roadmap 标记 ✅ 已完成）：`MemoryExtractor` 按 `PROFILE`/`PREFERENCE`/`INSTRUCTION`/`FACT` 四类抽取（这四个维度和 AgentScope/Mem0 的分类思路一致），但 `MemoryPromptFormatter` 是**按类型分组后全量拼进 system prompt**（验证：`MemoryStore`/`MemoryPromptFormatter` 全仓库搜索不到任何 `retrieve`/`vector`/`embed`/`topK`/`@Tool` 相关代码）——没有向量召回，没有 Top-K 过滤，也没有 `AGENT_CONTROL` 式的工具化记忆。

这不是这次核对才发现的新事实：roadmap 0.12 当时的验收备注原文是"短期历史层见 0.5，跨会话语义摘要层留给 Phase 4"。但现在 Phase 4（文件问答 RAG）已经标记完成，这条债务并没有真的被 Phase 4 还上——Phase 4 做的是文件的向量检索，不是记忆的向量检索，两者共享"向量召回"这个词但是完全独立的两条链路。**"跨会话语义摘要层"这条口子实际上还开着，只是被 Phase 4 完成的状态掩盖了。**

影响：单用户使用时间越长，记忆条目越多，`MemoryPromptFormatter` 全量注入会让 system prompt 里这部分持续膨胀，而且無法按当前问题做相关性过滤——这是一个会随时间推移变严重的隐性技术债，不是"当下能不能跑"的问题。

**建议**：不需要照抄 AgentScope 的三种模式，但至少应该有一条"记忆条目超过 N 条后转向量 Top-K 检索"的退化路径；并把这条在 roadmap 里显式标回"未完成"状态，不要让 Phase 4 的 ✅ 掩盖它。

---

## 四、行动项汇总

1. **新增 Runtime 级机制**："意图识别三层短路管道 + 单意图快车道/多意图 MasterAgent 编排" —— 补进 `roadmap.md`（Phase 8 或新开 Phase 2C），这是目前面试叙事里唯一一个高频问题完全没有代码支撑的缺口。
2. **接上 `StageOutputProvider` 的两个具体实现**：`ReferenceSourceProvider`（引用来源收尾输出）+ `SuggestedQuestionProvider`（工具能力边界感知的推荐问题）——机制已经在 `beforeComplete` 钩子上留好了位置，只是没人实现。
3. **RAG 全局问题能力**：给 Phase 4 补一条分层摘要路径（或者至少在文档里明确标注为已知限制、留给后续 Phase），否则"这篇文档讲了什么"这类问题目前答不好。
4. **长期记忆补退化路径**：`loop/memory` 加"记忆条目过多后转向量召回"的机制，并把 Phase 0.12 遗留的"跨会话语义摘要层"债务从"被 Phase 4 掩盖"改成 roadmap 里显式可见的未完成项。
5. **确认 Q7 的每轮反思注入是否是有意跳过**：如果是（认为 SKILL.md SOP + `maxRounds` 已经足够），补一条到 `engineering-pitfalls-and-highlights.md` 说明"为什么不需要"；如果是遗漏，加进 Phase 0.1 的补丁清单——面对工具调用能力偏弱的模型（私有化部署场景）时这条防线目前是空的。

## 参考资料

- Clippings（2026-08-05～08-06 新增）：`✅基于规则的L0L1意图识别详解`、`✅基于RAG的L2意图识别详解`、`✅基于规则+RAG+LLM构建三层意图识别`、`✅L1L2意图识别针对多意图的支持`、`✅单次对话多意图识别与执行`、`✅意图识别与问题改写的流水线编排`、`✅问题改写、意图识别、MasterAgent、SubAgent的执行顺序`、`✅用户意图很明确时，直接路由给子智能体还是主智能体？`、`✅引入L1L2之后意图识别不准确问题修复`、`✅AgentScope Java特性：长期记忆`、`✅dodo Agent 面试通关指南`、`✅讲讲你的dodo项目遇到哪些技术难点，以及你是怎么解决的`。
- 对照代码：`ToolCallExecutor.java`、`AgentLoopExecutor.java`、`StageOutputProvider.java`、`loop/memory/*.java`、`capability/rag/RagRetrievalService.java`、`capability/ppt/PptIntentRecognizer.java`。
