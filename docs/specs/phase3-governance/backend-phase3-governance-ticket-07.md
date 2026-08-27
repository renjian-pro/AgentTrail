# Ticket 7／9：Golden Set 扩展 + 真实样本筛选 + LLM-as-Judge + 压缩 trade-off 实测 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。不依赖 Ticket 1-6，
> 可以并行开工，但**依赖 Ticket 4 把 `TraceStore` 接入生产**——真实样本筛选工具要读的是生产环境
> 积累的 `agent_trace` 数据，Ticket 4 落地之前这张表在生产里是空的。Ticket 8（评测前端页面）
> `Blocked by` 这一票。

## 0. 范围边界

**这一票只做**：把 `GoldenTaskRunner` 从测试专用挪成生产可调用的评测服务、真实样本筛选工具、
LLM-as-Judge、压缩 trade-off 实测脚本。**不做**前端页面（Ticket 8）。

## 1. 开工前必须确认的现状：`GoldenTaskRunner` 现在整个在 `src/test/java` 下

已核实：`GoldenTaskRunner`/`GoldenCase`/`GoldenTaskReport`/`GoldenAssertion` 全部在
`src/test/java/com/agenttrail/analytics/golden/`，fixture YAML 走
`classpath*:analytics/golden/*.yml`（大概率在 `src/test/resources`，**先验证确切路径**）。
**这些类不在生产 JAR 的 classpath 里**——Ticket 8 要做"前端页面触发一次评测"，后端必须有一个
生产可调用的 HTTP 端点，而端点所在的 `src/main/java` 代码不能引用 `src/test/java` 的类
（Maven 标准布局下这是编译不通的）。

这一票要做的不是"复用 GoldenTaskRunner"这么简单，是**把评测能力从测试基础设施提升为生产能力**：

1. 把 `GoldenTaskRunner`/`GoldenCase`/`GoldenTaskReport`/`GoldenAssertion` 这一组类**搬到**
   `src/main/java/com/agenttrail/evaluation/`（新包），fixture YAML 搬到
   `src/main/resources/analytics/golden/`
2. `GoldenTaskLiveIT`/`GoldenTaskIT`/`GoldenTaskRunnerTest` 这三个测试文件改 import 路径，
   继续留在 `src/test/java`，不搬
3. fixture YAML 进生产 JAR 是一个**有意识的取舍**（这是个人项目/面试作品，不是要对外发布给
   第三方的产品，评测数据集本身不是需要保密的机密），Further Notes 里记一句，不需要为了"更
   严谨"去设计一套单独的资源加载机制

### 1.1 为什么不是"新写一个，测试那份继续留着"

那样会有两份几乎相同的加载/断言逻辑，后续维护两边都要改——直接搬迁，测试代码改 import，
是唯一不产生重复的做法。

## 2. Golden Set 扩展到多能力包

现有 28 条 fixture 全部是 SQL 数据分析场景（`dimension` 字段区分维度）。这一票新增覆盖对话/
文件问答/DeepResearch/PPT 的 fixture——**先验证**这四个能力包各自现在有没有已经写好的、可以
拿来当 fixture 参照的测试用例（比如 `AgentLoopExecutorFileTest`/`DeepResearchServiceTest`
这些集成测试里已经有真实的问答场景），能复用现成场景的直接转换成 YAML，不需要凭空编。

YAML 结构沿用现有 `analytics/golden/*.yml` 的字段（`id`/`dimension`/`question`/`as_user`/
`reference_sql`/`expected`/`assertions`）——非 SQL 场景没有 `reference_sql`，留空即可
（`GoldenCase` 该字段已经是 `text(item.get("reference_sql"))`，`null` 时返回空串，兼容）。

## 3. 真实样本筛选工具

新增一个从 `agent_trace`（Ticket 4 落地后的生产数据）筛选候选 fixture 的工具，借鉴
`spring-ai-alibaba/DataAgent` "从真实 trace 自动截取生成测试集"的设计思路，自己实现：

```java
package com.agenttrail.evaluation;

/** 从审计记录筛选 Golden Set 候选样本，产出待人工确认的候选列表，不直接写入正式 fixture。 */
public class GoldenCaseCandidateExtractor {
    // 输入：conversationId 范围/时间窗口/success=true 的记录
    // 输出：List<GoldenCaseCandidate>（question + 实际发生的 outputData，但不含"预期结果"——
    //       那必须由人工确认后才能填，见下面的强制约束）
}
```

**强制约束（必须在设计和实现里体现，不能省略）**：筛出来的候选**不能自动当作"正确答案"**直接
进入正式 Golden Set——真实调用记录只能证明"系统当时是这么回答的"，不能证明"这个回答是对的"。
候选列表需要一个人工确认步骤（哪怕只是导出一份 Markdown/CSV 让人过一遍再手动搬进正式 YAML），
这一票不做自动信任生产流量的设计。

## 4. LLM-as-Judge

新增 `LlmJudge` 组件，输入一个 `GoldenCase` + 实际的 `GoldenObservation`，输出结构化评分
（复用 0.15 的结构化输出机制 + JSON Schema，不是让模型自由文本打分再自己解析）。

评分维度（**先验证**这四个维度是不是覆盖了总纲里提到的"准确性/相关性/合规性"这类要求，
必要时增减，不要凭空定四个维度就当作最终答案）：
- 事实准确性（回答内容是否和 `expected`/`reference_sql` 的执行结果一致）
- 完整性（是否遗漏问题里明确要求的部分）
- 合规性（是否泄露了不该看到的数据——只对涉及数据权限的 case 有意义）

一致性校验：同一批 fixture 的 judge 结果跑两次，比较两次评分的差异——差异大的维度说明
judge prompt 对这个维度的判定标准不够收紧，需要在 prompt 里补充更明确的评分锚点（few-shot
样例），这一票要把"两次跑分方差"这个指标本身也纳入验收标准，不能只跑一次就当结果可信。

## 5. Agent 指标：工具选择准确率 / 参数准确率 / 不必要调用率

这三个指标**不靠 judge 主观打分**，靠对比 fixture 里标注的"预期工具调用"和
`GoldenObservation.toolCalls()`（已有字段，`GoldenTaskReport.java` 第 28 行附近）：

- `GoldenCase` 需要新增一个字段 `expectedToolCalls`（YAML 里新增一个可选的
  `expected_tool_calls` 列表，每项含工具名，不强制精确到参数值——参数级别的比对对多数场景
  过于严格，容易产生假阴性）
- 工具选择准确率 = 实际调用的工具集合和预期工具集合的交集 / 预期工具集合大小
- 不必要调用率 = 实际调用了但不在预期列表里的工具数 / 实际调用总数

**这一票只统计已标注 `expected_tool_calls` 的 case**——现有 28 条 fixture 大概率没有这个字段，
不强制回填全部旧 case，新增/改造时逐步补齐即可。

## 6. 压缩 trade-off 实测

新增一个独立的实测脚本/测试类（不是 Golden Set 本身的一部分，是**复用同一套 runner 骨架**，
变量从"Prompt 版本"换成"压缩策略"）：

- 固定构造一组 50 轮的对话历史（可以用现有的对话测试 fixture 拼接，或者新写一份专门的长对话
  fixture）
- 分别接入 `micro_compact`（`ContextPolicy` 现有的占位符替换策略——**先验证**具体配置项名字，
  读 `loop/context/ContextPolicy.java`）和 `auto_compact`（LLM 摘要）两种压缩策略跑同一组
  Golden QA case
- 记录两种策略下的：Golden QA 通过率（复用第 4 节的 judge 或者直接用现有断言）、单次请求延迟
- 产出一份对比表/图（这一票产出数据和结论文本即可，画图这种可视化工作可以留给 Ticket 8 的
  前端页面，不在这一票强制要求）

## 7. 后端 HTTP 端点（供 Ticket 8 前端调用）

```
POST /agent/v1/evaluation/run        触发一次 Golden Set 评测，返回一个可轮询的任务号
GET  /agent/v1/evaluation/{taskId}   查询评测进度/结果
GET  /agent/v1/evaluation/history    查看历史评测报告列表
```

异步模式沿用 DeepResearch/PPT 已经验证过的“提交即返回任务号 + 轮询”模式，不另起一套——
Golden Set 全量跑一遍可能需要几分钟到十几分钟（取决于 case 数量和真实模型调用延迟），不能是
同步阻塞的 HTTP 请求。

## 8. Testing Decisions

- 迁移回归：`src/main/java` 挪出来的 `GoldenTaskRunner` 相关类，`mvn test` 全绿，原有的三个
  测试文件改了 import 之后行为不变
- 真实样本筛选：给定一批模拟的 `agent_trace` 记录，验证筛选结果不包含 `success=false` 的记录，
  且候选列表的输出形式明确标注"待人工确认"，不是直接可用的正式 fixture
- LLM-as-Judge 一致性：同一批至少 10 条 case 跑两次 judge，记录并断言方差在预设阈值内
  （具体阈值这一票先给一个起点值，不是校准过的数字）
- Agent 指标：给定一个已标注 `expected_tool_calls` 的 case + 一份模拟的实际调用记录，验证
  三个指标的计算结果符合手算预期
- 压缩 trade-off：跑一次完整对比，产出的报告包含两种策略各自的通过率和延迟数字（不要求这一票
  跑出"结论"，跑出真实数字本身就是交付物）

## Out of Scope

- Golden Set 触发端点的鉴权细节（先假设走已有登录拦截器，管理员权限如果需要更细粒度另开票）
- 前端页面（Ticket 8）
- 压缩 trade-off 的可视化图表
