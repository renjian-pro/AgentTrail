# Runtime 能力缺口审计（2026-08-06 增量）

> 承接 [`dataagent-design-audit-2026-08-03.md`](dataagent-design-audit-2026-08-03.md)（下称“上一版”）。上一版覆盖 SQL 数据分析领域（Schema、权限、脱敏、SQL 安全）和登录体系；本次直接核对 AgentTrail 当前代码，补充统一意图路由、收尾输出、全局文档问答和长期记忆容量治理方面的真实缺口。

## 结论

最大的缺口不在某个 Capability Pack 内部，而是 **Runtime 层尚未形成“意图识别 + 多能力编排路由”的统一入口机制**。目前前端按 UI 入口分别调用不同 controller，后端缺少可解释、可测试的统一分流能力。除此之外还核实出三处“机制留了扩展点、但没有具体实现”的缺口（引用来源、推荐问题、长期记忆的语义召回），以及一处已由代码正确保证的能力（并发工具调用按原始顺序回填）。

---

## 一、新发现的重大缺口：分层意图识别与多能力编排

结合延迟、成本、可解释性和误路由风险，统一入口可采用三层短路策略：

- **L1 规则/关键词**（纯内存正则，<50ms，不调模型）
- **L2 向量相似度**（RAG embedding 检索，<100ms，只做 embedding 不调 LLM）
- **L3 LLM 兜底**（单轮、无工具、`maxIters=1`，不套 ReActAgent；输出严格 JSON）
- 三层输出统一的路由结果，至少包含候选意图、主意图、多意图标记、置信度和判定理由；下游调度不关心具体由哪一层命中
- 意图定义同时声明目标能力标识，路由层不再维护另一份易漂移的映射表
- **问题改写不是必经步骤**：先用原始问题执行 L1/L2，命中则跳过改写；未命中时才改写并重新执行 L1/L2，而不是直接进入 L3
- **单意图快车道**：高置信度 + 白名单 → 直接调用目标能力
- **多意图编排路径**：`multi_intent=true` 时交给编排器按依赖顺序调度各能力，避免任一单能力丢失其它意图

**AgentTrail 现状**：`grep -rl "意图\|Intent\|intent"` 全仓库命中的只有 `PptIntentRecognizer`（PPT 场景内部的 CREATE/MODIFY/RESUME 三分支固定标记判断，参见 `PptIntentRecognizer.java`）和 DeepResearch 的固定标记判断——两处都是**单一 Capability Pack 内部**的意图识别，不是 Runtime 级的跨能力包路由机制。`roadmap.md` 尚未把统一意图路由落成 Phase 表格中的机制、ticket 或 spec。

**为什么这是个真缺口而不是"暂时不需要"**：

1. AgentTrail 现在是“前端按入口分流”（Chat/PPT/DeepResearch/Analytics/RAG 各自独立 controller）；如果未来需要单一入口，当前后端缺少统一的分类、置信度和回退协议。
2. 机制本身不依赖任何新 Capability Pack：AgentTrail 已经有多个可路由目标（Analytics/DeepResearch/PPT/RAG Chat），可以独立验证，不必等待其它 Phase。
3. Phase 8 现有条目（`SubAgent` 工具化包装、`Orchestrator+Specialist+Reviewer`）解决的是“一个 Agent 内部调用子 Agent”，和“统一入口的意图路由分发”是两个不同层次的问题——后者发生在进入 Agent 循环之前，前者发生在循环内部。两者都需要，不能互相替代。

**建议**：在 `roadmap.md` 里补一个明确条目（可以并入 Phase 8，也可以单开 Phase 2C——因为它是 Runtime 级机制、无 Capability Pack 前置依赖），至少包含：
- 统一入口 + L1 规则 / L2 向量 / L3 LLM 兜底的三层短路管道，产出同构 JSON
- 问题改写 Agent（仅在 L1/L2 miss 时触发，改写后重新过一遍 L1/L2）
- 单意图高置信度直连目标能力 + 多意图交给编排器按依赖顺序执行的双分支路由

---

## 二、具体业务能力核对

以下结论均来自当前代码与项目目标之间的逐项核对：

| # | 审计要求 | AgentTrail 现状 | 结论 |
|---|---|---|---|
| A1 | 引用溯源应在流式收尾阶段输出结构化来源 | `StageOutputProvider` SPI 存在（`AgentLoopExecutor.java:1033` 已预留收尾输出），但全仓库没有具体 Provider 实现引用来源 | ❌ 缺口：扩展点尚未落地 |
| A2 | 推荐问题应感知当前可用工具和能力边界，避免生成无法执行的问题 | 同上，`StageOutputProvider` 无任何实现 | ❌ 缺口：同一个扩展点 |
| A3 | 弱工具调用模型可能在完成前过早收尾，需要验证是否加入轮次级反思提示 | `grep` 未命中每轮强制反思注入；现有机制是 `maxRounds` 强制收尾 + SKILL.md 的 SOP 指导 | ⚠️ 需通过目标模型回归测试决定，不预设实现 |
| A4 | 工具规模增长后需要分组、描述压缩、动态加载和检索 | AgentTrail 已有 Phase 0.6 ToolSearch（HYBRID：关键词优先，零命中才 fallback LLM），覆盖动态加载和部分工具索引；按 MCP 端点分组过滤未见实现 | ✅ 核心能力已覆盖，分组过滤可按规模补充 |
| A5 | 并发工具调用必须按原始 `tool_call` 顺序回填，否则模型会错位关联 | `ToolCallExecutor.execute()` 使用 `flatMapSequential` 保证并发执行但按源顺序回填 | ✅ 已正确实现 |
| A6 | 大文件全局问题需要分层摘要或全局/局部检索分流 | Phase 4 RAG 管线是查询压缩→多查询扩展→PgVector 相似度检索→去重，仍是 chunk 级局部检索 | ❌ 缺口：全局性问题容易答不完整 |

---

## 三、长期记忆容量治理

长期记忆可以采用三类控制模式；AgentTrail 不需要绑定某个框架，但应明确容量与召回策略：

- 系统控制：每轮自动检索注入，后台异步记录
- Agent 控制：以工具形式记录和召回，由模型判断时机
- 混合控制：默认检索兜底，同时允许模型显式操作
- 检索统一是**向量相似度 Top-K**，不是全量拼接

AgentTrail 的 `loop/memory` 包（Phase 0.12，roadmap 标记 ✅ 已完成）：`MemoryExtractor` 按 `PROFILE`/`PREFERENCE`/`INSTRUCTION`/`FACT` 四类抽取，但 `MemoryPromptFormatter` 是**按类型分组后全量拼进 system prompt**（验证：`MemoryStore`/`MemoryPromptFormatter` 全仓库搜索不到任何 `retrieve`/`vector`/`embed`/`topK`/`@Tool` 相关代码）——没有向量召回、Top-K 过滤或工具化记忆入口。

这不是这次核对才发现的新事实：roadmap 0.12 当时的验收备注原文是"短期历史层见 0.5，跨会话语义摘要层留给 Phase 4"。但现在 Phase 4（文件问答 RAG）已经标记完成，这条债务并没有真的被 Phase 4 还上——Phase 4 做的是文件的向量检索，不是记忆的向量检索，两者共享"向量召回"这个词但是完全独立的两条链路。**"跨会话语义摘要层"这条口子实际上还开着，只是被 Phase 4 完成的状态掩盖了。**

影响：单用户使用时间越长，记忆条目越多，`MemoryPromptFormatter` 全量注入会让 system prompt 里这部分持续膨胀，而且无法按当前问题做相关性过滤——这是一个会随时间推移变严重的隐性技术债，不是“当下能不能跑”的问题。

**建议**：至少增加“记忆条目超过 N 条后转向量 Top-K 检索”的退化路径，并把这条在 roadmap 里显式标回“未完成”状态，不要让 Phase 4 的完成状态掩盖它。

---

## 四、行动项汇总

1. **新增 Runtime 级机制**：“意图识别三层短路管道 + 单意图快车道/多意图编排” —— 补进 `roadmap.md`（Phase 8 或新开 Phase 2C）。
2. **接上 `StageOutputProvider` 的两个具体实现**：`ReferenceSourceProvider`（引用来源收尾输出）+ `SuggestedQuestionProvider`（工具能力边界感知的推荐问题）——机制已经在 `beforeComplete` 钩子上留好了位置，只是没人实现。
3. **RAG 全局问题能力**：给 Phase 4 补一条分层摘要路径（或者至少在文档里明确标注为已知限制、留给后续 Phase），否则"这篇文档讲了什么"这类问题目前答不好。
4. **长期记忆补退化路径**：`loop/memory` 加"记忆条目过多后转向量召回"的机制，并把 Phase 0.12 遗留的"跨会话语义摘要层"债务从"被 Phase 4 掩盖"改成 roadmap 里显式可见的未完成项。
5. **用目标模型验证每轮反思注入是否必要**：如果 SKILL.md SOP + `maxRounds` 已经足够，在 `engineering-pitfalls-and-highlights.md` 记录证据；如果存在过早收尾，再加入 Phase 0.1 的补丁清单。

## 审计证据

- 代码：`ToolCallExecutor.java`、`AgentLoopExecutor.java`、`StageOutputProvider.java`、`loop/memory/*.java`、`capability/rag/RagRetrievalService.java`、`capability/ppt/PptIntentRecognizer.java`。
- 验证方式：仓库级符号检索、扩展点实现盘点、现有测试与 roadmap 状态核对。
