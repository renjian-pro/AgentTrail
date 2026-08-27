# Ticket 03：HikariCP 连接池 + 后台线程池调参 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：① 给主 HikariCP 连接池（`PrimaryDataSourceConfig`）显式配一组
`spring.datasource.hikari.*` 参数，给出计算依据；② 把 `PptGenerationConfig.java:67`、
`DeepResearchConfig.java:70` 的 `Executors.newFixedThreadPool(4, ...)` 改成可配置线程数 + 有界
队列 + 拒绝策略。纯配置和线程池构造方式的改动，不涉及业务逻辑。

## 1. 主 HikariCP 连接池调参

### 1.1 现状

`PrimaryDataSourceConfig.java` 34-39 行：

```java
@Bean(name = "dataSource")
@Primary
@ConfigurationProperties(prefix = "spring.datasource.hikari")
public DataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
}
```

`@ConfigurationProperties(prefix = "spring.datasource.hikari")` 已经把绑定点留好了，但
`application.yml`/`application-local.yml` 里**没有任何一个 `spring.datasource.hikari.*` 键**
（`grep -rn "spring.datasource.hikari" src/main/resources` 零命中）——这个池现在完全吃 HikariCP
自己的默认值（`maximumPoolSize=10`），而它是 session/trace/memory/pause/ppt/golden/sys/auth
**所有** MySQL 存储共用的唯一池。

### 1.2 计算依据

抄 `AnalyticsDataSourceConfig.java` 的方式——它的池大小注释直接写"要对着 MySQL max_connections
折算"，主池也按同样的思路算，不是拍脑袋填数字：

- MySQL 8.x 默认 `max_connections = 151`（官方默认值，`db/schema.sql` 没有显式改过这个变量，
  **先验证**：实际部署用的 MySQL 实例是不是自定义过 `max_connections`，如果运维侧有单独配置，
  以实际值为准重新折算，本票的计算过程按默认值 151 示范）。
- 这台 MySQL 实例上目前有三个连接池会同时抢占连接数配额：主池（本票要配的这个）、
  `analytics` 只读池（`application.yml:130` 已显式设 `maximum-pool-size: 10`，默认不启用，
  `agenttrail.analytics.datasource.enabled=false`）、`pgvector` 池（`application.yml:76` 设
  `maximum-pool-size: 4`，连的是独立 Postgres 实例，不占 MySQL 的配额，这里只是同类对照）。
- 预留给 MySQL 自身系统连接、慢查询分析工具、运维直连（`mysql` client）等的余量，按经验值留
  20%（约 30 个），剩余 121 个可分配给应用连接池；`analytics` 池按已配置的 10 算，主池上限给
  **80**，留出约 30 的余量应对未来新增连接池或多实例部署（当前是单实例部署，多实例会让这个
  预算线性增长，需要重新折算，这次先按单实例配）。
- `connection-timeout`：HikariCP 默认 30 秒，这个项目的所有写操作（落库审计/追踪/会话）都不应该
  让请求线程等这么久——参照 `AnalyticsDataSourceConfig` 对只读池设的 `connectionTimeoutMs`
  语义（`Math.max(250, properties.getConnectionTimeoutMs())`，配置项默认 5000ms），主池同样给
  **5000ms**，超时说明池确实打满，快速失败比死等 30 秒更利于故障定位。
- `idle-timeout`：HikariCP 默认 10 分钟，主池流量模式是"持续有请求"而不是"潮汐式"，不需要激进
  回收空闲连接，保留默认值不额外配置（避免过度优化一个当前没有实际问题的维度）。
- `minimum-idle`：不显式配置，跟 HikariCP 推荐做法一致（`minimumIdle` 默认等于
  `maximumPoolSize`，即固定大小池——HikariCP 官方文档明确建议生产环境用固定大小池而不是动态
  伸缩池，动态伸缩在连接数尖峰创建的开销比固定池维持空闲连接的开销更值得付出）。

### 1.3 具体配置

`application.yml` 新增：

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 80
      connection-timeout: 5000
      pool-name: primary-pool
```

`pool-name` 是为了让启动日志/Actuator 指标里能区分是哪个池（`AnalyticsDataSourceConfig`/
`RagConfig` 已经分别设了 `analytics-pool`/`pgvector-pool`，主池此前没设，日志里只显示
`HikariPool-1` 这种默认命名，排查时不好对应）。

## 2. 后台线程池调参：`PptGenerationConfig` / `DeepResearchConfig`

### 2.1 现状

两处几乎一样的代码，`PptGenerationConfig.java:59-68`：

```java
@Bean(name = "pptGenerationExecutor", destroyMethod = "shutdown")
public ExecutorService pptGenerationExecutor() {
    AtomicInteger threadCount = new AtomicInteger();
    ThreadFactory namedDaemonThreads = runnable -> {
        Thread thread = new Thread(runnable, "ppt-generation-" + threadCount.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    return Executors.newFixedThreadPool(4, namedDaemonThreads);
}
```

`DeepResearchConfig.java:62-71` 是同样结构，线程名前缀换成 `deep-research-`。
`Executors.newFixedThreadPool` 内部用的是**无界 `LinkedBlockingQueue`**——线程数固定 4，
但排队的任务数没有上限，突发提交量超过 4 个并发处理能力时，后续任务会在堆内存里无限堆积，
是真实的 OOM 风险点（`refactor-blueprint.md` §1.5 已经点出，这里是具体修复）。

### 2.2 参考"做对的例子"：`ToolCallExecutor` 的有界原则

`ToolCallExecutor.java:71-74`：

```java
private static final Scheduler TOOL_EXECUTION_SCHEDULER = Schedulers.newBoundedElastic(
        Schedulers.DEFAULT_BOUNDED_ELASTIC_SIZE,
        Schedulers.DEFAULT_BOUNDED_ELASTIC_QUEUESIZE,
        "agent-tool-exec");
```

这是 Reactor 的 `Scheduler`，不是 `ExecutorService`，两者 API 不同，不能直接套用调用方式——但
要抄的是"有界"这个原则本身：`newBoundedElastic` 除了限制线程数，**同时**限制了排队任务数
（`DEFAULT_BOUNDED_ELASTIC_QUEUESIZE`，超出后新任务直接被拒绝/报错，不会无限堆积）。
`PptGenerationConfig`/`DeepResearchConfig` 要复刻的正是这个"线程数 + 队列容量都有上限"的组合，
不是简单地把 `newFixedThreadPool` 换成另一个同样无界的写法。

### 2.3 具体实现

```java
@Bean(name = "pptGenerationExecutor", destroyMethod = "shutdown")
public ExecutorService pptGenerationExecutor(
        @Value("${agenttrail.ppt.executor.pool-size:4}") int poolSize,
        @Value("${agenttrail.ppt.executor.queue-capacity:20}") int queueCapacity) {
    AtomicInteger threadCount = new AtomicInteger();
    ThreadFactory namedDaemonThreads = runnable -> {
        Thread thread = new Thread(runnable, "ppt-generation-" + threadCount.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    };
    ThreadPoolExecutor executor = new ThreadPoolExecutor(
            poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            namedDaemonThreads,
            new ThreadPoolExecutor.AbortPolicy());
    return executor;
}
```

`DeepResearchConfig` 同样改法，配置键换成 `agenttrail.deepresearch.executor.*`。

### 2.4 队列容量与拒绝策略的选择理由

- **队列容量给 20**：不是拍脑袋——PPT/DeepResearch 都是分钟级任务（PPT 类注释里写"8 个状态、
  含 LLM 调用+文生图+Python 渲染子进程"，DeepResearch 是"需求澄清→计划→检索→综合"整条链路），
  4 个线程满载时，20 个排队任务意味着最坏情况下新提交的任务要等接近 5 倍处理时间才会被取到
  （假设每个任务耗时相近）。这个项目当前没有真实压测数据支撑更精确的数字，20 是"给正常的
  短时突发留余量、但不让积压变得对用户不可接受"这个原则下的示例值，和 §3d 里 SLO 数值同样
  性质——面试能讲清楚"为什么是这个数"的合理估算，不是校准过的生产数值，后续有真实流量画像后
  应该重新调整。
- **拒绝策略选 `AbortPolicy`（抛异常），不选 `CallerRunsPolicy`**：这两个线程池背后是用户主动
  提交的 PPT/DeepResearch 任务，提交入口是 HTTP 请求线程（`PptGenerationService.create`/
  `DeepResearchService.research`）。`CallerRunsPolicy` 会让 Tomcat 请求线程自己同步跑完这个
  分钟级任务，等价于把"异步任务"降级成"同步阻塞请求"——用户会看到请求挂起几分钟直到超时，
  比明确报错体验更差，还会连带拖慢 Tomcat 线程池处理其它正常请求的能力。选择
  `AbortPolicy` 抛 `RejectedExecutionException`，在 `PptGenerationService`/`DeepResearchService`
  的提交入口 catch 住，转成一个"系统繁忙，请稍后重试"的明确错误返回给前端——用户能立刻知道
  任务没有被接受，而不是拿到一个静默丢失的任务或者一次意外的长时间同步等待。

**先验证**：`PptGenerationService.create`/`DeepResearchService.research` 现在提交任务给
`ExecutorService.submit(...)` 之后有没有已经在处理 `RejectedExecutionException` 的 catch 块——
如果没有，这次要顺手加上，让拒绝真的能传导成一个用户可见的错误响应，不能只是把线程池改成有界
就结束，那样只是把"堆内存 OOM"换成"任务被拒绝但调用方拿到一个未捕获异常导致的 500"，同样是
静默失败的一种。

## Testing Decisions

- HikariCP：应用启动后检查日志（Hikari 启动时会打印 pool name 和 `maximumPoolSize` 等配置项）
  确认用了新配置，不是默认的 `HikariPool-1`/10；可选：通过 `/actuator/prometheus` 的
  `hikaricp_connections_max` 指标核实数值。
- 线程池：单测层面构造一个 `poolSize=1, queueCapacity=1` 的小号线程池，提交 3 个阻塞任务，
  验证第 3 个提交直接抛 `RejectedExecutionException`（不阻塞、不静默丢失）；集成层面验证
  `PptGenerationService`/`DeepResearchService` 捕获这个异常后返回的是一个明确的"系统繁忙"错误
  响应，不是原始异常堆栈或者 500。
- 回归验证：正常负载下（并发数不超过 `poolSize + queueCapacity`）功能行为和改动前一致，任务
  正常提交、正常执行、正常拿到结果。

## Out of Scope

- 真实生产环境下重新压测校准 `maximum-pool-size`/队列容量的数值——这次给的是有计算依据的
  合理估算，不是压测校准值，压测需要真实流量画像，这次没有条件做。
- `server.tomcat.max-threads`/`accept-count` 等全局并发上限配置（`refactor-blueprint.md` §1.5
  提到但明确是另一个独立问题）——这次只处理已经点名的 HikariCP 和两个后台线程池。
- `ToolRateLimiter` 默认禁用的问题（`agenttrail.redis.enabled: false`）——这是限流机制本身的
  开关问题，不是连接池/线程池调参范畴，不在这一票处理。
