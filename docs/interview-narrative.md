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
| 持久化必须在 `tryEmitComplete` 之前，doFinally 里会因 JVM 退出丢数据（#63，agentx 源码真实事故） | Reactor 终止信号时序（doOnComplete vs doFinally） |
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
| 并发控制 | 无 | 单飞注册 + Redis 分布式锁 + Lua 原子续期 + 幂等工具模板（#30/#34） | Phase 1 |
| 失败恢复 | 抛异常结束 | 分级：LLM 基础设施错误代码重试 / 工具错误喂回模型自愈 / HITL 暂停恢复 / maxRounds 熔断 | Phase 0/1 |
| 可观测性 | print 日志 | OTel 链路 + TTFT 独立埋点 + 采样策略 + SLO/告警 + token 成本熔断（#38/#39） | Phase 3 |
| 测试 | 跑通 main | ScriptedLlmClient 确定性回放 + 分片重组模糊测试 + Testcontainers 真库（禁 H2） | Phase 0.0 |
| 部署 | java -jar | Compose→K8s、liveness/readiness 分离、优雅停机接 PauseState、配置中心+Vault | Phase 11 |
| 安全 | 无 | Prompt Injection 检测、工具速率限制、审计哈希链、PII 打码、MCP 暴露安全线（#40/#41） | Phase 3 |

---

## 三、叙事技巧备忘

1. **每条都从场景开头**："我项目里有个具体场景"永远好过"这个知识点是这样的"。
2. **主动亮缺陷**：代码走查里发现的真实缺陷（#65 事务不回滚、#67 术语用错、#71 注释漂移）比正确案例更稀缺——"我读别人代码能挑出这种 bug"是 code review 能力的直接证明。
3. **对比着讲**：两种方案并存的地方（Reactor Context vs TTL、mega-tool vs read_skill、Graph vs 自由 loop、状态位扫描 vs Outbox）永远比单方案有讨论深度。
4. **承认边界**：#45 单跳依赖、#44 状态粒度 checkpoint 这类"已知局限+改造思路"的回答，比"我的设计没问题"可信得多。
5. **演进即故事**：ADR 0001→0002 的推翻过程、Skills 两段式→mega-tool、压缩选择性保留→全量——每条演进线都按"先怎么做→撞了什么→为什么改"讲。
