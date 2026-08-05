# Java + AI 面试叙事手册

> 核心方法（来自验证报告，完全采纳）：**用项目工程细节把八股"引"出来，而不是背八股**。
> 每条叙事都走三段式：**项目里遇到什么坑 → 怎么解 → 对应 Java 哪个知识点**。
> 面试官问 Java 基础时，答案的开头永远是"我项目里有个具体场景……"，让八股变成工程证据。
> 所有 `#N` 引用指向 `engineering-pitfalls-and-highlights.md`，每条都可以往下追到具体机制。

---

## 一、三条叙事主线

### 主线 A：并发与线程（从 ReAct loop 引出）

**引子**：我的 Agent Runtime 是手写的流式 ReAct loop——一轮 = 一次流式 LLM 调用 + 一批并发工具执行 + 递归下一轮。这条链路上全是并发问题。

| 项目场景（坑→解） | 引出的八股锚点 |
|---|---|
| 流式 tool_call 参数分片按 id 重组，拼完才校验（#1） | 流式协议下的状态累积模式（类比 SAX/gRPC streaming） |
| 工具并发执行但结果必须按请求顺序拼回——`flatMapSequential` 不是 `flatMap` | Reactor 操作符语义差异；并发执行 ≠ 乱序返回 |
| `Disposable` 每轮必须重新注册，否则中断拿到的是第一轮的旧句柄（#9） | 过期引用导致控制失效；资源句柄生命周期管理 |
| `stopTask` 用原子 `remove` 不是 get-then-remove（#10） | `ConcurrentHashMap` 的 check-then-act 竞态 |
| 工具池和聚合池必须隔离，共用 boundedElastic 互相拖死（#62） | 线程池隔离（舱壁模式）、boundedElastic 默认参数 |
| 持久化必须在 `tryEmitComplete` 之前，doFinally 里会因 JVM 退出丢数据（#63，参考框架源码真实事故） | Reactor 终止信号时序（doOnComplete vs doFinally） |
| 无界 backpressure buffer 在"长输出+慢消费"下 OOM；超时要拆 TTFT/token 间隔两档（#64） | 背压四种溢出策略；timeout 只断下游要配 cancel |
| MDC/trace 跨线程丢失：Reactor Context vs TransmittableThreadLocal 两种方案对比（#39） | ThreadLocal 在异步场景失效的根因与两条修复路线 |
| 用 `Thread.ofVirtual()` 做异步标题生成，失败降级保留临时标题——但裸起虚拟线程无背压无重试 | 虚拟线程适用场景（IO 密集）与边界（无池化管理） |
| 反射取 reasoning_content，`Method` 按 Class 缓存 + volatile 双检（#4） | 反射性能优化、volatile 可见性、双重检查 |

### 主线 B：Spring 生态（从架构决策引出）

**引子**：为什么不用框架自动执行工具、为什么直调 `ChatModel.stream` 绕开 Advisor 链——这个决策本身就是 Spring 生态理解深度的展示（见 ADR-0002）。

| 项目场景（坑→解） | 引出的八股锚点 |
|---|---|
| Spring AI 1.x `internalToolExecutionEnabled` → 2.0 移除、工具循环挪进 `ToolCallingAdvisor`；直调 ChatModel 绕开整层，版本无关 | 框架边界判断：什么时候该把控制权从框架拿回来 |
| `@DistributeLock`：`@Order(MIN_VALUE)` 保证锁切面在事务切面之外——先加锁再开事务 | AOP 切面执行顺序、锁与事务的相对位置 |
| 同一个切面 `catch Throwable 包成受检 Exception`，导致 `@Transactional` 静默不回滚（#65，真实缺陷） | Spring 事务回滚规则（RuntimeException vs 受检） |
| `@Async` 监听器读到未提交事务 → `@TransactionalEventListener(AFTER_COMMIT)`（#66，源码注释记录的教训） | 事务可见性、事件监听 phase、异步线程池配置 |
| 多 Agent 注册表：放弃自维护 Map，prototype bean + beanName 约定推导 + `getBean`（#69，集群下自注册白名单失效） | bean scope 与有状态对象、约定优于配置 |
| SkillsTool/LookupGlossary 用 `FunctionToolCallback` 而不是 `@Tool` 注解——因为 description 需要运行时拼接 | 注解值必须编译期常量这个限制的工程绕法 |
| 日志脱敏：自定义 logback `conversionRule` 白名单精确匹配，替换 houbb 全文扫描（误伤中文日志） | logback 扩展点、误报率 vs 漏报率权衡 |
| 生产配置：`@RefreshScope` + 配置中心 + Vault；见过反面案例：根 pom 硬编码 key + resource filtering 打进 jar（#72） | 配置外部化、Maven filtering 副作用、密钥轮换 |
| 手动 `new ObjectMapper()` 绕开 JSON starter 隐式装配，序列化 `Instant` 直接运行时炸——Boot 自动配置的 `ObjectMapper` 会自动扫描注册 JSR-310 模块，自己 `new` 的不会（#88） | Spring Boot 自动装配到底替你做了什么；"能编译≠能跑对"在序列化场景的具体案例 |

### 主线 C：数据与一致性（从会话/权限/补偿引出）

**引子**：Agent 的会话状态、SQL 权限、事件补偿全是一致性问题——这是后端架构师转 AI 最能打的主场。

| 项目场景（坑→解） | 引出的八股锚点 |
|---|---|
| 权限 AST 改写：字符串拼接会被 OR 优先级绕过、LEFT JOIN 条件放 WHERE 退化成 INNER JOIN（#26，全文档含金量最高） | SQL 语义、AST 操作、为什么正则/拼接从原理上不行 |
| Redis 分布式锁：归属校验+续期必须原子（Lua），优雅关闭主动释放不等 TTL（#30/#31） | Redisson watchdog、分布式锁续期正确性 |
| 补偿 Job：状态位扫描重放，但声明的重试上限/延迟窗口没用上——补偿和正常流程赛跑（#68） | 最终一致性三要素、Outbox vs 状态位扫描 |
| 空值缓存无 TTL 挡住后续真实数据；穿透/击穿术语在源码注释里就用错了（#67） | 缓存三大问题的准确定义、缓存与 DB 一致性 |
| 熔断：Redis+Lua 原子计数，降级是"从工具列表移除"让 LLM 看不见，而不是拦截调用（#71） | 熔断三态、Lua 原子性、Agent 特有的"可见性降级" |
| HITL 暂停恢复：把"等人"从线程状态变成数据状态，PauseState 快照+按 Reason 分支恢复（#61） | workflow 引擎核心思想、有状态服务在 K8s 上的断点续执 |
| 工具幂等性：框架只记录 SafePoint 阶段，幂等责任显式留给工具方——唯一键 upsert/幂等 token/Outbox 三选一（#34） | 至少一次语义、幂等设计三板斧 |
| 会话历史重建按 token 预算倒推 N，不是拍脑袋定数（Phase 0.5） | 容量规划思维在 LLM 场景的映射 |

---

## 二、demo vs 工程落地对照清单（面试的"差异化自证"）

能把左右两列的差异讲清楚，本身就是"我做的不是 demo"的证明。缺口列已按最新路线图状态更新。

| 维度 | demo 做法 | 本项目工程级做法 | 状态 |
|---|---|---|---|
| 工具执行 | 框架内部自动跑，过程黑盒 | 自建 `ToolCallExecutor`：并发执行+按序回填+非法 JSON 降级空参数（#1/#2） | 设计已验证 |
| 上下文管理 | 截断保留最近 N 条 | micro/auto 两层压缩+保护名单+失败回退（#6-8），压缩 trade-off 有实测曲线计划 | 设计已验证 |
| 会话持久化 | 内存 ChatMemory | JDBC 双 key（conversation/session）+ token 预算重建 + tryEmitComplete 前同步入库（#49/#63） | Phase 0.5 |
| SQL 安全 | 正则黑名单 | AST Visitor + 六层防御 + `setMaxRows(N+1)` + 权限 AST 改写 + 别名防绕过脱敏（#22-26） | Phase 2 |
| 并发控制 | 无 | 单飞注册 + Redis 分布式锁 + Lua 原子续期 + 幂等工具模板（#30/#34） | Phase 1，生产装配已接线（见下方"多实例任务管理"） |
| 失败恢复 | 抛异常结束 | 分级：LLM 基础设施错误代码重试 / 工具错误喂回模型自愈 / HITL 暂停恢复 / maxRounds 熔断 | Phase 0/1 |
| 治理：审批 + 预算 | 无 | HITL 高危操作人工审批（`PauseConfig` 首次生产接线）+ 会话级 token 预算熔断（`SessionBudgetTracker`） | ✅ Phase 3（#64） |
| 治理：审计 | print 日志 | `TraceStore` 首次生产接线 + SHA-256 哈希链防篡改（`verifyChain`） | ✅ Phase 3（#66） |
| 可观测性 | print 日志 | OTel 链路 + TTFT 独立埋点 + 采样策略 + SLO/告警 + Prometheus/Grafana/Langfuse 部署（#38/#39） | ✅ Phase 3（#67/#68） |
| 评测 | 手工试几个问题 | Golden Set 生产化 + LLM-as-Judge（含一致性方差校验）+ Agent 指标（工具选择/参数/不必要调用准确率）+ 压缩 trade-off 实测 | ✅ Phase 3（#69/#70） |
| 测试 | 跑通 main | ScriptedLlmClient 确定性回放 + 分片重组模糊测试 + Testcontainers 真库（禁 H2） | Phase 0.0 |
| 部署 | java -jar | Compose→K8s、liveness/readiness 分离、优雅停机接 PauseState、配置中心+Vault | Phase 11（本地 Compose 已有，K8s 未做） |
| 安全 | 无 | Prompt Injection 检测（小模型分类）、工具速率限制（Redisson RRateLimiter）、PII 打码、Bash 工具凭据隔离（真实发现并修复了环境变量泄露，#89）、MCP 暴露安全线（#40/#41） | ✅ Phase 3（#71） |

---

## 三、叙事技巧备忘

1. **每条都从场景开头**："我项目里有个具体场景"永远好过"这个知识点是这样的"。
2. **主动亮缺陷**：代码走查里发现的真实缺陷（#65 事务不回滚、#67 术语用错、#71 注释漂移）比正确案例更稀缺——"我读别人代码能挑出这种 bug"是 code review 能力的直接证明。
3. **对比着讲**：两种方案并存的地方（Reactor Context vs TTL、mega-tool vs read_skill、Graph vs 自由 loop、状态位扫描 vs Outbox）永远比单方案有讨论深度。
4. **承认边界**：#45 单跳依赖、#44 状态粒度 checkpoint 这类"已知局限+改造思路"的回答，比"我的设计没问题"可信得多。
5. **演进即故事**：ADR 0001→0002 的推翻过程、Skills 两段式→mega-tool、压缩选择性保留→全量——每条演进线都按"先怎么做→撞了什么→为什么改"讲。

---

## 四、补充高频问答：架构选择与复杂流程

以下内容用于补强项目叙事。回答时必须以当前代码和部署形态为准：已经接线的能力可以讲实现细节；只完成组件或设计验证的能力，要明确说“组件已具备，生产装配/验收尚未完成”。

### 为什么不直接使用低代码 Agent 平台或黑盒 ReAct？

本项目需要把工具调用、流式事件、上下文压缩、会话持久化、取消和业务状态机作为可观测、可替换的工程模块来演进。低代码平台适合简单流程的快速验证；当需求涉及多模型路由、工具并发后的有序回填、PPT 断点恢复等跨阶段控制时，保留 Runtime 控制权更利于定位问题和逐步演进。

这不是“自研一定优于平台”的结论，而是针对长期维护、深度定制和现有 Java/Spring 体系的取舍。项目以 Spring 的依赖注入、配置管理和测试体系承接运行时能力；Python 只负责 PPT 渲染这一擅长的边界，通过受超时控制的进程调用与 Java 编排层协作。

### ReAct Loop 如何结束，又如何避免模型过早停止？

一次循环包含模型决策、工具调用和工具结果回填。正常结束由模型在已有信息足够时输出最终文本；同时 Runtime 以 `maxRounds` 作为硬上限，用户也可对普通 SSE 对话发起停止。工具调用完成后，Runtime 把结果作为下一轮上下文的一部分，让模型根据新事实继续决策。

稳定性不能只依赖模型：流式工具参数按调用标识重组，完成整轮后才解析；单个工具失败会转为结构化结果回传给模型，而不是立即让整条循环崩溃。模型能力较弱、容易一轮就结束时，应在下一轮提示中明确要求它检查证据是否充分、缺什么信息、是否仍需调用工具，并用回放测试覆盖这种行为。

### Deep Research 如何避免跑偏并收敛？

流程先澄清问题并生成研究主题；每轮由 Planner 生成带 `order` 的任务，按层串行、层内受并发上限控制地执行。Critic 输出结构化的 `passed/feedback`，未通过时把反馈作为下一轮计划的增量约束；最终 Summarizer 同时接收原始问题、研究主题和累积检索结果。主题锚点、职责分离和反馈回路共同限制研究方向，`maxCritiqueRounds` 防止无限循环。

多轮结果会消耗上下文，因此运行时的两层压缩与 Deep Research 的研究上下文压缩分开处理：前者服务 ReAct 子循环，后者服务研究结果与批判反馈。压缩只能减少模型上下文，不能改变 API 返回的原始任务结果。

### 为什么工具可以并发执行，却仍要按原顺序返回？

同一轮里互不依赖的工具可以并发，以缩短总耗时；但下一次模型调用需要能把响应对应回原始调用。因此 Runtime 先保留模型给出的调用顺序，并发执行后按该顺序聚合结果。并发时必须限制并发度、隔离阻塞工具、把单工具错误降级为对应的工具响应，并确保流式参数已完整拼接后才执行。

### PPT 为什么采用状态机和跨语言渲染？

PPT 是跨多个高失败概率步骤的长流程。任务状态和上下文快照持久化后，每个阶段由独立策略推进；失败后可从已有 checkpoint 恢复，避免重复生成需求、提纲或 Schema。Java 负责状态编排、持久化和 API，Python 负责 PPTX 渲染。跨语言边界必须具备依赖检查、超时终止、stdout/stderr 排空和结构化错误传播；Schema 使用临时文件传递，避免超长命令行参数。

### 多实例任务管理的正确表述

`RedisTaskLock` 和 `RedisInterruptBroadcaster` 已提供跨实例归属、广播停止和锁续期的实现与测试基础；2026-08-02 的安全审计发现生产 `AgentLoopExecutorConfig.agentTaskManager()` 当时确实还只是裸 `new AgentTaskManager()`，这个 P0 已经补上——现在按 `ObjectProvider<RedissonClient>.getIfAvailable()` 判断：没配 Redis（这台开发机默认如此）时降级成和以前完全一致的纯内存单实例行为；配了真实 Redis 地址（`agenttrail.redis.enabled=true`）之后才真正启用跨实例锁 + Pub/Sub 广播，并显式调用 `RedisTaskLock.startAutoRenewal()` 开启后台自动续期。表述上要精确：**机制已经生产接线**，但"跨实例互斥/跨实例取消"这个能力本身有没有在多实例环境里真正验收过，仍然要看部署时是否配置了 Redis 并做过实测——`RedisTaskLockIT`/`AgentTaskManagerCrossInstanceIT` 覆盖的是"机制在真实 Redis 上行为正确"，不等于"生产多实例部署已经跑过"。
