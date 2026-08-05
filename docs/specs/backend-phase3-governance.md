# AgentTrail 后端 — Phase 3 治理层（Hooks + 审计 + 可观测性 + 评测 + 安全纵深）需求 Spec

> 状态：草案，按 `to-spec` 模板整理，尚未发布为 GitHub issue。
> 前提：Phase 0-2 已全部完成并关闭（issue #1-#61）。`docs/roadmap.md` 原定"技术债清完再进 Phase 3"的四项里，
> Redis 任务锁生产装配、ReAct 失败预算、Golden Task 真实执行链三项已经核实完成；只剩
> **DeepResearch/PPT 不能取消、刷新页面接不回正在跑的任务**这一项还没还，这次并入本 spec 一起交付（见下方 3a）。
> 关联文档：`docs/roadmap.md` Phase 3 表格是这份 spec 的上位设计意图，`docs/validation-report.md`
> "缺口 2/3/5"是对 Phase 3 原表格的工程细节补充，两者已经吸收进本文各节，不需要再单独对照阅读。
> 披露规则：同其余 spec，方法论表述为"研读了真实生产形态治理设计后独立实现"；`spring-ai-alibaba/DataAgent`、
> Langfuse、houbb/sensitive 均为公开开源项目，roadmap/ADR 已直接点名引用，这里沿用同样的处理，不做匿名化。

## Problem Statement

Runtime 核心和 SQL 数据分析能力包都已经交付，但完全没有治理层，暴露出五类具体缺口：

1. **没有 Hook 拦截点，风险操作和只读操作没有代码层区别对待。** 现在唯一的"防线"是 `SKILL.md`
   里的文字约束（"禁止使用 Shell、文件系统和命令行绕过分析工具访问数据"），这是靠模型自觉遵守的
   prompt 层规则，不是代码强制——和 Hooks 设计的第一原则（"Prompt 不是唯一的安全防线，权限过滤/
   越权拦截必须在代码层强制实现"）直接冲突。
2. **0.14 TraceAudit 已经全量落库审计数据，但没有防篡改能力。** 任何有数据库写权限的人都能悄悄
   改掉一条 `agent_trace` 记录而不留痕迹，"审计"这个词目前只对外部观察者成立，对内部人员没有约束力。
3. **完全没有可观测性基础设施。** `pom.xml` 里连 `micrometer`/`opentelemetry` 依赖都没有引入过，
   TTFT 这类关键体验指标没有任何埋点，出问题只能靠翻应用日志人工排查，看不到趋势、没有告警。
4. **Golden Task 基础设施已经在 Phase 2 SQL 里跑通（`GoldenTaskRunner` + 28 条 fixture + 一次真实
   执行报告），但只覆盖 SQL 单个能力包，没有走向可以回答"整体准不准"的系统化评测体系**——也没有
   LLM-as-Judge，没有跨能力包的通用指标。
5. **没有任何 Prompt Injection / 工具调用限速 / PII 防护。** 现在完全依赖模型自己判断"这段用户输入
   像不像攻击"，一旦 ReAct 自我修正陷入死循环，除了粗粒度的 `maxRounds=20` 硬顶和已有的连续失败
   熔断（`consecutiveToolFailures`），没有更早的节流手段；用户输入里的手机号/身份证号会原样上送模型。
6. **遗留技术债**：DeepResearch/PPT 异步任务能提交、能轮询查询，但没有取消端点，前端刷新页面后
   也接不回一个还在跑的任务——本该在 Phase 2 收尾时还上，一直拖到现在。

## Solution

把 3a-3e 五块和第 6 条技术债一起交付，贯穿全篇的四条设计原则：

1. **复用已有基础设施优先，不重复造轮子**——高危操作审批复用已有的 `PauseState` HITL 快照机制；
   成本聚合读 `TraceAudit` 已经落库的 token 字段；ReAct 失败熔断（`consecutiveToolFailures`）已经
   是代码强制机制，这次只是把它提炼成一条通用的 Capability Pack 编写规范。
2. **业务语义的治理逻辑自研，通用技术能力用标准协议，两者职责分开**——审计防篡改（哈希链）是
   业务语义，自己实现；可观测性走标准 Micrometer+OTel 协议，输出到任何兼容后端，不发明自己的
   埋点格式。
3. **评测体系自建，不引入 Spring AI Alibaba Admin 这类独立平台**——理由见下方 Implementation
   Decisions，核心是它要求接入 `spring-ai-alibaba-autoconfigure-arms-observation`，这条依赖假设
   应用走 `ChatClient`/Advisor 链，和本项目 ADR-0002 已经定过的"直连 `ChatModel.stream()`"架构决策
   冲突；借鉴它"从真实 trace 自动截取生成测试集"的设计思路，但自己实现，不引入整个平台。
4. **A/B 测试明确排除在这次范围外**（见 Out of Scope）。

## User Stories

### 3a：Hooks 生命周期 + 权限分级 + 中断恢复

1. 作为系统维护者，我想让 Agent 生命周期的六个关键点（会话开始/工具调用前后/预算检查/异常/
   会话结束）都有一个明确的拦截点，以便治理逻辑不用散落在业务代码各处
2. 作为系统维护者，我想让只读工具直接放行、写类/高危工具必须经过人工审批才能执行，以便"模型
   想执行什么"和"系统允许执行什么"是两件独立的事，不依赖模型自觉
3. 作为审批人，我想在高危操作被拦截时收到完整信息（谁、什么操作、什么参数），并能批准或拒绝，
   以便介入时不用去翻日志现查
4. 作为用户，我想能主动取消一个正在跑的 DeepResearch/PPT 任务，以便不用干等一个不想要的结果跑完
5. 作为用户，我想刷新页面后如果之前提交的任务还在跑，能重新接上进度显示，而不是刷新完什么都看不到
6. 作为系统维护者，我想任何新增 Capability Pack 的 `SKILL.md` 都被要求显式声明重试预算，以便
   "防止 ReAct 自我修正死循环"这件事有统一规范，不是每个能力包各想各的

### 3b：审计防篡改

7. 作为审计人员，我想确认审计记录一旦写入就不能被静默篡改，以便"查到的记录就是真实发生过的"
   这件事有技术保证，不是纯粹依赖信任
8. 作为系统维护者，我想在怀疑某条记录被改过时能快速验证哈希链是否完整，而不需要逐条人工比对

### 3c：可观测性闭环

9. 作为系统维护者，我想看到 TTFT、端到端首响应、工具调用成功率的实时面板，以便判断系统是否在
   正常范围内运行
10. 作为系统维护者，我想在某个 SLI 突破阈值时收到告警，而不是等用户投诉才发现
11. 作为系统维护者，我想查看某一次具体请求的完整调用链路耗时分布，以便定位性能问题出在哪一步

### 3d：评测体系

12. 作为系统维护者，我想有一份覆盖多个能力包的 Golden Set，跑一遍就能拿到当前系统的准确率数字，
    以便"你们准不准"这个问题有数据支撑
13. 作为系统维护者，我想在改动 Prompt/模型/阈值配置后重新跑一遍 Golden Set 并和之前的结果对比，
    以便判断这次改动是变好还是变差
14. 作为系统维护者，我想能从真实审计记录里筛选候选样本转成 Golden Set fixture，而不是只能手写
    固定用例
15. 作为系统维护者，我想实测 micro/auto 两种上下文压缩策略在信息保留率和延迟上的 trade-off，
    以便这个设计决策有数据支撑而不是直觉判断

### 3e：安全纵深

16. 作为系统维护者，我想在用户输入进入 system prompt 之前做 Prompt Injection 检测，以便"忽略
    以上所有指令"这类攻击不能直接生效
17. 作为系统维护者，我想给单会话的工具调用设置速率上限，以便 ReAct 死循环不会无限烧 token
18. 作为系统维护者，我想在用户输入送进 LLM 之前对 PII 做检测和打码，以便敏感数据不会被上送到
    模型服务商
19. 作为系统维护者，我想确认 Agent 可控的执行环境（Bash 工具等）不会被注入真实的数据库/模型/
    对象存储凭据，以便即使工具被诱导执行恶意操作，损害范围也有边界

## Implementation Decisions

### 3a-1：Hook 接口与注册

六个拦截点对齐已有设计稿（`E:\study\AI\porject\wiki\projects\dev-efficiency-agent-portfolio.md`
的 Hooks 设计表）：`SessionStart`/`PreToolUse`/`PostToolUse`/`Budget`/`OnError`/`SessionEnd`。

接入方式延续项目已经定过的取舍——不用 Spring AOP/Advisor 链（呼应"直连 `ChatModel.stream()`，
不经过 Advisor"的架构决策），Hook 是显式接口 + `@Order` 排序的 Bean 列表，在 `loop/core` 内部
显式调用，编排逻辑集中在 `AgentLoopExecutor`（对齐 AGENTS.md 代码约定"一个类一个职责，编排逻辑
集中在 AgentLoopExecutor"）。每个 Hook 类型是一个独立接口（`SessionStartHook`/`PreToolUseHook`/…），
允许同一拦截点挂多个实现，按 `@Order` 顺序执行，任一实现可以短路后续执行（比如 `PreToolUseHook`
判定需要转人工审批时，后面的 Hook 不应该再跑）。

`SessionStart`/`OnError` 这两个点目前的逻辑已经分散在各处实现了（用户身份解析在 Phase 2 auth
ticket、`consecutiveToolFailures` 熔断在 `AgentLoopExecutor#finishRound`）——这次不是新增机制，
是把已有逻辑收拢到统一的 Hook 拦截点上，让"这里能加治理逻辑"这件事自解释。

### 3a-2：工具风险分级

工具定义加一个风险等级字段：`READ_ONLY`/`WRITE`/`HIGH_RISK`。`listTables`/`describeTables`/
`lookup_glossary`/`execute_sql` 等标 `READ_ONLY`；`write_file`/`edit_file`/`Bash` 标 `HIGH_RISK`。
**Ticket 1 实现时核实过**：`execute_sql` 内部走 `ReadOnlyQueryRunner`，工具描述原文是"执行只读
分析 SQL"——Phase 2 SQL 能力包目前没有任何写库工具，当前代码库里 `HIGH_RISK` 只出现在通用
Runtime 层（`write_file`/`edit_file`/`bash`），不在 analytics 能力包里；这里最初写"executeSql
标 HIGH_RISK"是没查代码的错误猜测，已改正。`PreToolUseHook` 读这个字段做分流：`READ_ONLY` 直接
放行；`HIGH_RISK` 转人工审批（3a-3）——这次先按二档（只读放行 / 高危拦截）实现，不引入 `WRITE`
中间档，避免过度设计一个暂时用不上的第三档。

### 3a-3：高危操作转人工审批（复用 PauseState）

不新建审批机制——`PreToolUseHook` 判定 `HIGH_RISK` 时，走和现有"暂停恢复"同一条路径：产生一份
`PauseState` 快照（当前会话上下文 + 待执行的工具调用），会话进入等待状态；管理员在前端审批页面
看到"待审批"列表，approve 触发已有的恢复分支正常执行；reject 不是直接结束会话，而是把拒绝原因
作为工具执行失败结果回传给模型，让它据此重新规划（这样模型能看到"为什么被拒绝"，而不是一次
不透明的中断）。

### 3a-4：Budget Hook

读取 3b 落库的 `agent_trace` token 字段，按 `sessionId`/自然日聚合，超过预设阈值时 `BudgetHook`
直接熔断（返回预算超限错误，终止当前轮）。"超预算降级到更便宜模型"这个更精细的形态这次不做，
留 Further Notes——熔断已经能满足"成本不失控"这个核心诉求，降级需要额外的模型能力对等性判断，
不是这次的必要范围。

### 3a-5：DeepResearch/PPT 取消 + 刷新恢复（技术债并入）

**开工前必须验证**：DeepResearch/PPT 当前是"异步提交 + 轮询查询"模型，需要先确认这两条链路
现在有没有注册进 `AgentTaskManager`（Phase 0.4 已有的单飞注册 + 原子 `stopTask`）。如果没有
注册，取消端点要先把这层绑定补上，不能假设"停止机制已经在，只是没暴露接口"。

- **取消端点**：`POST /agent/deepresearch/{taskId}/cancel`、`POST /agent/ppt/{taskId}/cancel`，
  复用 `AgentTaskManager.stopTask`；取消后任务状态落库为 `CANCELLED`（不是 `FAILED`，语义上要
  能区分"用户主动停的"和"系统跑失败的"）。
- **刷新恢复**：提交任务时返回 `taskId`；新增 `GET /agent/tasks?status=RUNNING`（按当前登录
  用户过滤）供前端在页面加载时查询"我有没有还在跑的任务"，拿到 `taskId` 后重新建立轮询。这条
  依赖 Phase 2 auth 已经落地的登录态，不需要额外的身份机制。

### 3a-6：重试预算作为 Capability Pack 编写规范

不是代码交付物。`consecutiveToolFailures` 熔断机制和 `data-analysis` SKILL.md 里"最多重试 2 次"
的声明已经是正确的设计（验证过仍然成立，不需要改）——这次要做的是把它写成一条**通用规范**，
补进 `docs/agents/domain.md` 或新增一份 Capability Pack 编写指南：任何新 Skill 的 `SKILL.md`
必须显式声明单任务最大重试预算，不能只依赖 `maxRounds` 兜底。这样 Phase 5/6/7 之后新增能力包时，
这条不会被漏掉。

### 3b：审计哈希链

在 `agent_trace` 表上加两列：`prev_hash`、`hash`，不新建表——哈希链和记录是 1:1 关系，拆表没有
必要。`JdbcTraceStore` 落库那一刻顺便算好这两个字段，不产生额外的写放大。

哈希算法：对记录关键字段（不含 `hash` 自身）做 SHA-256，拼接上一条记录的 `hash`，得到当前 `hash`
——标准哈希链模式，不需要发明新算法。

**链的粒度按 `sessionId` 分，不做全局单链**：同一会话内的记录才有真正的因果顺序关系，跨会话
记录之间不存在"篡改关联"的意义；按会话分链后，写入时只需要查"这个 session 最后一条记录的
hash"，锁粒度是会话级而不是全局级，并发写入冲突概率低得多——这是这一节最关键的设计取舍，
如果按全局单链设计，会引入一个不必要的全局写入序号竞争点。

验证：提供一个管理接口/工具，从某个 `sessionId` 的第一条记录开始重算一遍哈希链，和落库值逐条
比对，不一致即判定为被篡改，返回第一个不一致的记录位置。

### 3c：可观测性闭环

**依赖新增**（需要先验证和 Boot 4.1.0 的兼容性，不确定的地方不要假设）：
`spring-boot-starter-actuator` + `micrometer-registry-prometheus` + `micrometer-tracing-bridge-otel`
+ `opentelemetry-exporter-otlp`。

**埋点位置**：
- LLM 调用耗时（按模型名打 tag）
- TTFT 单独一个 `Timer`，从请求开始到第一个流式 chunk 到达——必须和总耗时分开埋（踩坑点 #38），
  两者是两个独立的 Timer，不能共用一个再拆分计算
- 每个工具调用的耗时 + 成功/失败 `Counter`（按工具名打 tag）
- Reactor 跨线程的 trace/span 传播：0.10 已有的 MDC 跨线程传播是业务日志自定义字段，这里要确认
  Micrometer 的 `ObservationRegistry` 能否同样挂到 Reactor Context 上，两套传播机制需要对齐，
  不是天然打通的，要写测试验证

**采样**：自定义 `io.opentelemetry.sdk.trace.samplers.Sampler`
（`ParentBased(TraceIdRatioBased(0.1))` + 错误请求 100% 采样），Spring Boot 默认的
`management.tracing.sampling.probability` 只支持固定比例，做不到条件采样，必须自己实现。

**数据去向**：Prometheus 只吃 metrics；trace 走 OTLP 送到 Langfuse（它本来就是按 OTLP 消费
LLM trace 设计的，不需要另起 Tempo/Jaeger）。两条线共用同一份 OTel 埋点，各自消费自己需要的
部分。

**SLO**：三个 SLI——TTFT P95 < 2s、端到端首响应 < 5s、工具调用成功率 > 99%，数值是面试可讲清楚
"为什么是这个数"的示例值，不是真实压测校准结果，这次不追求校准精度。Grafana Alerting 规则能
触发、UI 上能看到 SLO 破线即算完成，不接真实 Slack/邮件通道。

**部署**：`docker-compose.yml` 新增 `prometheus`/`grafana`/`langfuse` 三个 service，复用 Phase 11
已经计划的本地开发 compose 编排。

### 3d：评测体系

**Golden Set 扩展**：不再局限于 Phase 2 SQL 的 28 条 fixture，覆盖尽量多能力包（对话/文件问答/
DeepResearch/PPT，按各能力包当前的稳定程度决定纳入优先级）。

**真实样本筛选工具**：借鉴 Spring AI Alibaba Admin"从真实 trace 自动截取生成测试集"的设计思路，
自己实现——从 `agent_trace` 里筛选真实请求转成候选 fixture，而不是只能手写固定用例；筛选出来的
候选需要人工确认预期结果后才能真正进入 Golden Set，不能自动信任真实调用记录本身是"正确答案"。

**LLM-as-Judge**：需要设计 judge prompt、评分维度、一致性校验——同一批 fixture 跑两次 judge，
比较判分方差，方差过大说明 judge prompt 需要收紧评分标准，不能只跑一次就当作可信结果。

**Agent 指标**：工具选择准确率、参数准确率、不必要调用率——这几个指标的计算依赖 fixture 里标注
"预期应该调用的工具+参数"，跑完对比实际调用记录（读 `agent_trace` 的工具调用轨迹）算出比率，
不是靠 judge 主观打分。

**压缩 trade-off 实测**：固定 50 轮对话，对照 micro_compact（占位符替换）与 auto_compact（LLM
摘要）两种压缩策略下的 Golden QA 准确率与延迟，复用 Golden Set 的 runner 框架，只是把"变量"从
Prompt 版本换成压缩策略，画出 trade-off 曲线。

**前端**：做成现有 Vue3 前端里的一个页面（触发评测、查看历史报告、两次报告对比），不新建独立
Admin 应用——评测面对的是维护者自己，不是团队多角色协作，没必要复刻独立平台的复杂度。

### 3e：安全纵深

**Prompt Injection 防护**：用户输入进 system prompt 前，用一次小模型调用做意图分类，检测"忽略
以上指令"类攻击模式；工具返回值进 prompt 前做隔离标记，防止工具返回内容里混入的指令性文本被
模型当成新指令执行。

**工具调用速率限制**：单会话单位时间工具调用上限，Redis 滑动窗口实现（复用 Phase 1 已有的
Redis 基础设施，不新开一套）；命中限速或者本身是 `HIGH_RISK` 分级的工具（`write_file`/`edit_file`/`Bash`）
走 3a-3 的人工审批路径，不是简单拒绝了事。

**PII 检测**：用户输入进 LLM 前做检测打码，参考 `houbb/sensitive`（公开开源脱敏库）的规则集
设计思路，具体实现可以直接依赖它或者只借鉴规则集结构自己实现，视依赖体积和可定制性权衡决定。

**工具执行环境凭据隔离**：这条是架构审查/加固清单，不一定有很大新增代码量——梳理现有 Bash 工具
的执行边界，确认它拿不到数据库/模型 API Key/对象存储的真实凭据，这是从 DataAgent 沙箱安全边界
（"不向沙箱注入数据库/模型/OSS/Docker 凭据"）借鉴的原则，同样适用于我们已有的 Bash 工具（Phase 0.9）。

## Testing Decisions

- Hook 拦截测试：模拟一次 `HIGH_RISK` 工具调用，验证走了 `PreToolUseHook` 转人工审批而不是
  直接执行；reject 后验证模型收到的是"被拒绝"错误而不是会话直接结束
- 哈希链测试：篡改一条记录后校验能检测出来并定位到具体位置；正常连续写入的多条记录链完整；
  同一 `sessionId` 并发写入场景下链不会断裂（并发测试是这一节的重点，不能只测单线程场景）
- 可观测性测试：Prometheus 抓取端点返回预期指标名；TTFT 和总耗时是两个独立 Timer，断言两者
  数值不相等（验证确实分开埋点，不是共用一个数值拆分计算）
- Golden Set 测试：跑一次评测产出的报告字段完整；LLM-as-Judge 一致性测试（同一批 fixture 跑
  两次，判分方差在可接受范围内）
- 安全测试：已知 Prompt Injection 攻击样本能被拦截；工具调用速率限制超阈值请求被拒绝；PII
  打码测试（身份证/手机号样本输入，落库和日志里都不能出现明文）
- 越权/取消测试：账号 A 不能取消账号 B 的任务；取消后的任务状态是 `CANCELLED` 不是 `FAILED`
- 集成测试延续项目一贯约定，起真实 MySQL/Redis（Testcontainers），不用 H2 替代

## Out of Scope

- **A/B 测试**（在线灰度分流、离线对照实验的完整机制）——已明确决定这次不做；3d 的评测 harness
  配置可参数化这件事本身是评测体系的自然产物，不需要为 A/B 专门设计 variant tagging/`SessionStart`
  挂载机制
- 真实 Slack/邮件告警通道对接——Grafana Alerting 规则触发、UI 能看到即可
- Tempo/Jaeger 等独立分布式追踪后端——用 Langfuse 替代，不重复造一套
- Spring AI Alibaba Admin 或任何独立评测/管理平台的引入（理由见 Solution）
- Budget Hook 的"降级到更便宜模型"形态——这次先做熔断，降级留后续
- 真实生产级 PII 检测的准确率保证——这次是"有这道工序"，不追求达到商用 DLP 产品的准确率
- `WRITE` 风险档的独立处理逻辑——先按二档（只读放行/高危拦截）实现

## Further Notes

### Ticket 拆分（implementation-ready 逐票文档已全部写完，GitHub issue 已建好）

| Ticket | Issue | 范围 | Blocked by |
|---|---|---|---|
| 1/9 | [#63](https://github.com/renjian-pro/AgentTrail/issues/63) | Hooks 骨架（六个拦截点接口 + 注册机制）+ 工具风险分级 | 无 |
| 2/9 | [#64](https://github.com/renjian-pro/AgentTrail/issues/64) | `PauseConfig` 首次生产接线 + 高危操作人工审批 + 会话级 Budget 熔断 | #63 |
| 3/9 | [#65](https://github.com/renjian-pro/AgentTrail/issues/65) | DeepResearch/PPT 取消端点 + 刷新恢复（顺带修复 DeepResearch 缺失的越权校验） | 无（可并行） |
| 4/9 | [#66](https://github.com/renjian-pro/AgentTrail/issues/66) | `TraceStore` 首次生产接线 + 审计哈希链（按 conversationId 分链） | 无 |
| 5/9 | [#67](https://github.com/renjian-pro/AgentTrail/issues/67) | Micrometer+OTel 埋点 + 自定义 Sampler + Reactor Observation 传播 | 无 |
| 6/9 | [#68](https://github.com/renjian-pro/AgentTrail/issues/68) | 部署侧 Prometheus/Grafana/Langfuse + SLO 面板 + 告警规则 | #67 |
| 7/9 | [#69](https://github.com/renjian-pro/AgentTrail/issues/69) | `GoldenTaskRunner` 迁到生产代码 + Golden Set 扩展 + 真实样本筛选 + LLM-as-Judge + 压缩 trade-off 实测 | #66（真实样本筛选依赖 `TraceStore` 生产数据） |
| 8/9 | [#70](https://github.com/renjian-pro/AgentTrail/issues/70) | 评测前端页面（跑评测/看报告/对比报告） | #69 |
| 9/9 | [#71](https://github.com/renjian-pro/AgentTrail/issues/71) | 安全纵深：Prompt Injection + 工具调用限速 + PII 检测 + 凭据隔离审查 | #63（限速挂在 `PreToolUse` 上） |

九张逐票技术文档在 `docs/specs/backend-phase3-governance-ticket-0{1..9}.md`，写的过程中核实
代码发现了几处会让原计划走偏的地方（已经改到票里，不是纸面设计）：`execute_sql` 其实是只读
工具、`PauseConfig`/`TraceStore` 从来没有接入生产装配（issue #17/#13 只是"机制验证完成"，
不等于"生产在用"）、`GoldenTaskRunner` 整套在 `src/test/java` 下不在生产 classpath 里、
DeepResearch 的 `status` 端点缺一个越权校验。1/3/4/5/7 五张没有前置依赖，可以并行开工。

### 这次会被复用的已有机制（避免重新设计）

- `PauseState` 快照 + 恢复（issue #13）→ 3a-3 高危操作审批
- `AgentTaskManager.stopTask`（Phase 0.4）→ 3a-5 DeepResearch/PPT 取消
- `consecutiveToolFailures` 熔断（`AgentLoopExecutor#finishRound`）→ 3a-6 编写规范来源
- `TraceAudit`/`JdbcTraceStore`（issue #17）→ 3b 哈希链的宿主表、3a-4 Budget Hook 的 token 数据源
- `GoldenTaskRunner` + 28 条 fixture（Phase 2 SQL）→ 3d 评测体系的执行骨架
- Redis 基础设施（Phase 1 分布式锁）→ 3e 工具调用速率限制的滑动窗口存储
- Phase 2 auth 的登录态 → 3a-5 刷新恢复的任务归属过滤

### 依赖本 spec 完成后的收尾

- Phase 3 完成、closed-loop 走完一遍后，roadmap.md 的技术债清单可以正式清零，"下一步不是直接进
  Phase 3"这句话失效，回到按 Phase 4-11 依赖关系排序的正常节奏
