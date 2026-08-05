# Ticket 5／9：Micrometer+OTel 埋点 + 自定义 Sampler — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。不依赖其它票，可以
> 并行开工。Ticket 6（部署侧 Prometheus/Grafana/Langfuse）依赖这一票产出的指标端点。

## 0. 范围边界

**这一票只做**：引入依赖、埋点、自定义采样器、Reactor 跨线程传播验证。**不做**任何部署编排
（docker-compose/Grafana 面板/告警规则，那是 Ticket 6）。

## 1. 开工前必须验证：依赖版本和 Boot 4.1.0 的兼容性

`pom.xml` 当前 parent 是 `spring-boot-starter-parent:4.1.0`，**目前没有引入任何**
`micrometer`/`opentelemetry` 相关依赖（已核实，`pom.xml` 全文搜索确认）。这是这一票真正的
不确定性来源——Spring Boot 4.x 的 `spring-boot-starter-actuator` 对 Micrometer/Micrometer
Tracing 的版本管理是通过 Boot 的依赖管理 BOM 自动锁定的，理论上不需要手动指定版本号，但
**必须先跑一次 `mvn dependency:tree` 确认解出来的版本组合能正常工作**，不要凭记忆假设：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

验证步骤（和 `backend-phase2-auth-ticket-01.md` 第 2 节同一套方法论）：
1. 只加依赖，`mvn dependency:tree`，检查有没有版本冲突/被排除的关键依赖
2. `mvn spring-boot:run` 或一个最小 `@SpringBootTest` 上下文加载测试，确认应用正常启动
3. 验证结果（实际解出来的版本号、有没有需要显式覆盖的传递依赖）记录成 commit message 或这份
   文档的追加说明

## 2. 埋点位置

### 2.1 LLM 调用耗时 + TTFT（分开埋，不能共用一个 Timer 拆分计算）

改动点在 `AgentLoopExecutor.scheduleRound`（原第 457-468 行，`llmInvoker.streamRound(...)` 的
订阅链）。当前链路是：

```java
Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
        .publishOn(Schedulers.boundedElastic())
        .doOnNext(chunk -> processChunk(chunk, state, context))
        .doOnComplete(() -> finishRound(state, context, requestSnapshot))
        .doOnError(error -> failRun(error, context, state, requestSnapshot))
        .onErrorComplete()
        .subscribe();
```

TTFT 的"第一个 chunk"判定不能用 `doOnNext` 里简单加一个 `boolean firstSeen` 标记后手动
`Timer.record(...)`——更符合 Micrometer 习惯用法的做法是用 `Timer.Sample` 手动打点：
在发起订阅前 `Timer.Sample sample = Timer.start(meterRegistry)`，`doOnNext` 里第一次进入时
`sample.stop(ttftTimer)`（用一个 `AtomicBoolean`/`RoundState` 里加一个字段判断"是不是第一次"，
`RoundState` 已经是这一轮的可变状态容器，加一个 `boolean firstChunkSeen` 字段是最小改动）；
整轮耗时另开一个独立的 `Timer.Sample`，在 `doOnComplete`/`doOnError` 里各自 `stop`——**这是
两个独立的 `Timer.Sample` 实例，不是同一个 sample 中途读两次**，对应"TTFT 和总耗时必须分开埋"
这条要求（踩坑点 #38）。

两个 Timer 都按模型名打 tag（`RegisteredModel`/`ChatModel` 能拿到模型标识的地方，具体从哪个
参数传进 `scheduleRound` 需要看现有代码怎么传模型标识——**先验证**：`AgentLoopExecutor` 内部
目前有没有持有"当前模型名"这个信息，如果没有，需要作为构造参数/Builder 项加一个）。

### 2.2 工具调用耗时 + 成功/失败 Counter

改动点在 `ToolCallExecutor.executeOne`（`loop/core/ToolCallExecutor.java` 第 108-139 行）。
`tool.call(arguments)` 前后包一个 `Timer.Sample`，`success`/`failure` 两个分支各自
`.tag("outcome", "success"/"failure")` 记录，按 `toolCall.name()` 打 `tool` tag。

`ToolCallExecutor` 目前是包私有类，构造函数只接受 `List<ToolCallback>`（第 54 行）——这里要
新增一个可选的 `MeterRegistry` 参数（null 表示不启用，和这个代码库里"传 null 关闭机制"的
一贯风格一致），构造函数需要加一个重载（不是必须的第一个参数，避免破坏调用方）。

### 2.3 Reactor 跨线程的 Observation 传播——这一票唯一需要实际写测试验证的不确定点

0.10 已有的 `MdcPropagation`（`loop/core/MdcPropagation.java`）解决的是 SLF4J MDC 这个
ThreadLocal 状态跨越到 `TOOL_EXECUTION_SCHEDULER` 线程的问题，用的是手动"快照 + 还原"。
Micrometer 的 `ObservationRegistry`/当前 trace span 上下文是**另一套独立的传播机制**，
理论上应该通过 Reactor 的 `Context`（`reactor.util.context.Context`，不是 Java 的
`ThreadLocal`）自动传播，但这个代码库的工具执行路径最终会 `.block()`
（`ToolCallExecutor.execute` 第 104-105 行的 `.collectList().block()`）——**先验证**：
`.block()` 这一步会不会切断 Reactor Context 的自动传播链条，如果会，需要参考
`MdcPropagation` 现成的"手动快照+还原"模式，为 Micrometer 的 trace 上下文也做一遍同样的处理，
不能假设"用了 Reactor 就自动有传播"。这一票必须为这一点写一个显式的集成测试（第 4 节）。

## 3. 自定义 Sampler

`management.tracing.sampling.probability` 只支持固定比例，做不到"错误请求 100% 采样"。
新增一个 `Sampler` Bean：

```java
package com.agenttrail.observability;  // 新包，和 loop 核心逻辑解耦

@Bean
public Sampler agentTraceSampler() {
    Sampler base = Sampler.parentBased(Sampler.traceIdRatioBased(0.1));
    return new Sampler() {
        @Override
        public SamplingResult shouldSample(Context parentContext, String traceId, String name,
                SpanKind spanKind, Attributes attributes, List<LinkData> parentLinks) {
            // 错误请求 100% 采样的判定时机是个真实的工程难点：shouldSample 在 span **开始时**调用，
            // 这时候还不知道这次调用最后会不会失败——标准 OTel Sampler 模型本身不支持
            // "先记录、失败了再决定要不要保留"这种事后采样。这一票先按 ParentBased+比例采样实现，
            // "错误 100% 采样"需要用 Span Processor 层面的 tail-based 采样或者应用层在 catch 块
            // 里手动强制标记（比如给 span 加一个属性，导出端按属性过滤），选哪种在实现时再定，
            // 这里只标注这是一个需要具体方案的开放问题，不是可以直接抄的确定实现。
            return base.shouldSample(parentContext, traceId, name, spanKind, attributes, parentLinks);
        }
        @Override
        public String getDescription() {
            return "AgentTrailSampler{parentBased(ratio=0.1), error-100%-todo}";
        }
    };
}
```

**这一节比其它节更"不确定"**——"错误请求 100% 采样"这个需求在标准 OTel Sampler 模型下没有
教科书式的直接实现方式，需要实现时进一步调研（tail-based sampling 是业界标准解法，但通常需要
一个独立的 Collector 做二次决策，不是应用内一个 Sampler Bean 能完全做到的）。这一票先把
`ParentBased(TraceIdRatioBased(0.1))` 落地，错误全采样作为已知的后续增强点写进 Further Notes，
不要为了"看起来完整"而写一个实际不生效的伪实现。

## 4. Testing Decisions

- TTFT 和总耗时是两个独立 Timer：断言两次记录的数值不相等（用一个人为加了流式延迟的测试
  ChatModel，确保两者有可观测的差异，不是恰好数值相同导致断言没意义）
- 工具调用 Timer/Counter：跑一次成功 + 一次失败的工具调用，断言两个 outcome tag 分别记了一次
- **Reactor 跨线程传播的显式测试**（第 2.3 节的核心验证目标）：在工具执行阶段（已经跳到
  `TOOL_EXECUTION_SCHEDULER` 线程）里读当前 trace span，断言和发起请求时的 span 是同一个
  trace（哪怕不是同一个 span），不能是空/不相关的上下文
- Prometheus 抓取端点集成测试：`GET /actuator/prometheus` 返回包含预期的指标名
  （`agenttrail.llm.duration`/`agenttrail.llm.ttft`/`agenttrail.tool.duration` 这类命名，
  具体命名规则遵循 Micrometer 的点分命名约定）
- 依赖兼容性验证结果（第 1 节）本身也要留痕——不是测试，是要求这一票的 PR/commit 描述里
  写清楚验证过程和结论

## Out of Scope

- OTel Collector/Langfuse/Prometheus/Grafana 的部署编排（Ticket 6）
- "错误请求 100% 采样"的完整实现（第 3 节已说明是开放问题，先记录不实现）
- SLO 告警规则（Ticket 6）
