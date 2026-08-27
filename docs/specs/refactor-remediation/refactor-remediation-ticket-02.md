# Ticket 02：CI 接入 Testcontainers 类集成测试 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：① 新增一个不需要外部密钥/本地长驻服务的 Maven profile，只 include 真正自包含的
Testcontainers 类 IT；② 把这个 profile 接入 `.github/workflows/ci.yml`；③ 给需要外部密钥/本地
长驻服务的 IT 补跳过门槛，本地误跑 `-P integration` 不再直接失败。不改动现有 `integration`/
`golden` 两个 profile 的行为，也不新增/删除任何一个 IT 测试类本身的断言逻辑。

## 1. 先验证：题面给的"SharedMySql 相关 IT 也算自包含"这个假设是错的

题面原话是"只 include 已确认用 Testcontainers 自包含的 IT 类——...以及用到 `SharedMySql` 的那些
IT"——**这个假设读代码后发现不成立**。读 `pom.xml` 296-310 行的构建脚本注释和
`src/test/java/com/agenttrail/support/SharedMySql.java` 的类注释，两处都明确写着：

> "MySQL 集成测试连的是开发机上常驻的实例（见 `SharedMySql`），不用 Testcontainers 现拉现起
> 容器——这台机器上 Testcontainers 起一个全新 MySQL 要等 initdb 初始化数据目录，慢到分钟级；
> 直接连常驻实例更快。"

`SharedMySql.jdbcUrl()`/`username()`/`password()` 全部读的是 `LocalConfig`（本地
`application-local.yml`），**不是 `@Testcontainers`/`@Container` 注解驱动的一次性容器**。这些
IT 需要这台机器上已经有一个跑起来的 MySQL 实例、且配好了 `agenttrail` 库——GitHub Actions 的
干净 runner 上没有这个前提，直接归进"Testcontainers 自包含"会导致 CI 全部失败，不是"零成本接入
回归保护"。

反过来，真正满足"`@Testcontainers` + `@Container` 驱动、不连接任何本机常驵服务"的 IT，
grep 全部 28 个 `*IT.java` 后确认是 **4 个**，全部基于 `redis:7-alpine` 的
`GenericContainer`，都不需要 `SharedMySql`：

| 类 | 路径 | 验证的能力 |
|---|---|---|
| `RedisTaskLockIT` | `src/test/java/com/agenttrail/loop/task/` | 分布式任务锁的归属校验/续期/抢占 |
| `AgentTaskManagerCrossInstanceIT` | `src/test/java/com/agenttrail/loop/task/` | 跨实例单飞（Redis Pub/Sub 广播取消） |
| `ToolRateLimiterIT` | `src/test/java/com/agenttrail/loop/security/` | 工具调用限速的真实 Redis 行为 |
| `AgentLoopExecutorConfigRedisWiringIT` | `src/test/java/com/agenttrail/web/config/` | **题面列的三个之外遗漏的第四个**——验证 `AgentLoopExecutorConfig.agentTaskManager` 生产装配真的接上了 Redis 锁，不是裸 `new AgentTaskManager()` |

`AgentLoopExecutorConfigRedisWiringIT` 是这次核对时才发现的遗漏，题面原话提醒"不要假设我在
§4.4 列的四个类是全部"，实际找到的确实不止题面举例的那三个。

## 2. 完整分类清单（28 个 `*IT.java`，本次逐个 grep 确认）

| 分类 | 类 | 归属 profile |
|---|---|---|
| Testcontainers 自包含 | `RedisTaskLockIT`、`AgentTaskManagerCrossInstanceIT`、`ToolRateLimiterIT`、`AgentLoopExecutorConfigRedisWiringIT` | 新增 `integration-ci`（本票核心） |
| `golden` 专用（已有独立门槛） | `GoldenTaskIT`（`@EnabledIfSystemProperty("agenttrail.golden.enabled")`）、`GoldenTaskLiveIT` | 已有 `golden` profile，不动 |
| 需要 `SharedMySql`（本机常驻 MySQL，不适合无状态 CI runner） | `GoldenCaseRepositoryIT`、`SysPermissionServiceIT`、`JdbcPauseStateStoreIT`、`JdbcMemoryStoreIT`、`JdbcTraceStoreIT`、`JdbcFileStoreIT`、`JdbcPptTaskStoreIT`、`JdbcSessionStoreIT`、`AgentLoopExecutorRestartRecoveryIT`、`AgentLoopExecutorFullStackIT`、`WebSearchAgentLoopIT`*、`ChartAgentLoopIT`*、`MemoryAgentLoopIT`、`DeepResearchServiceIT`*、`ImageDescriptionIT`*、`PptGenerationServiceIT`*、`ImageStrategyIT`*、`SearchStrategyIT`*、`RagPipelineIT`* | 仍归 `integration`，不进 `integration-ci` |
| 需要外部密钥/本地长驻服务（标 * 的上面已经列了，以下是仅此原因、不涉及 SharedMySql 的） | `ChartToolProviderIT`（本地 mcp-echarts + MinIO）、`TavilySearchToolProviderIT`（Tavily key）、`DeepSeekLlmClientLiveIT`（DeepSeek key，`legacy` 包） | 仍归 `integration`，本票补跳过门槛（见 §4） |

标 * 的类**同时**需要 `SharedMySql` 和外部密钥/服务（比如 `WebSearchAgentLoopIT` 既要连
`SharedMySql` 又要真实 Tavily/DashScope key），两个理由任一个不满足都跑不了，双重排除在
`integration-ci` 之外。

## 3. 新增 `integration-ci` profile

抄 `pom.xml` 347-368 行 `integration` profile 的写法（覆盖 surefire 的 include/exclude），但只精确
`<include>` 到 §2 确认的 4 个类，不是笼统重新 include 全部 `**/*IT.java`：

```xml
<profile>
    <id>integration-ci</id>
    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <excludes combine.self="override"/>
                    <includes combine.self="override">
                        <include>**/*Test.java</include>
                        <include>**/*Tests.java</include>
                        <include>**/*TestCase.java</include>
                        <include>**/RedisTaskLockIT.java</include>
                        <include>**/AgentTaskManagerCrossInstanceIT.java</include>
                        <include>**/ToolRateLimiterIT.java</include>
                        <include>**/AgentLoopExecutorConfigRedisWiringIT.java</include>
                    </includes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</profile>
```

**先验证**：这四个类各自开一个新的 `GenericContainer<>(DockerImageName.parse("redis:7-alpine"))`
（各自独立 `@Container` 字段，不共享容器实例）——本地/CI 跑 `-P integration-ci` 时会拉起 4 个
独立 Redis 容器，起停总耗时在秒级（Redis 镜像没有 initdb 步骤，`RedisTaskLockIT` 类注释里已经
确认过），不会像 MySQL 那样慢，不需要额外做容器复用优化。

## 4. CI 接入

`.github/workflows/ci.yml` 45-46 行的 `Build and test` 步骤后面加一步：

```yaml
      - name: Build and test
        run: ./mvnw -B -ntp verify

      - name: Run self-contained Testcontainers integration tests
        run: ./mvnw -B -ntp verify -P integration-ci
```

GitHub Actions 的 `ubuntu-latest` runner 自带 Docker daemon，Testcontainers 能直接拉镜像跑，
不需要额外的 `services:` 声明（那是给"整个 job 期间常驻"的服务用的，Testcontainers 是按需拉起
一次性容器，两种机制不冲突但也不需要混用）。**先验证**：runner 上第一次跑需要从 Docker Hub 拉
`redis:7-alpine`，确认 GitHub Actions 对 Docker Hub 的匿名拉取没有被限流卡住（截至目前
`docker.io` 匿名拉取限额是每 IP 每 6 小时 100 次，一次 CI 跑 4 个容器远低于这个量级，正常不会
触发限流；如果后续镜像拉取偶发失败，再考虑换成 GitHub Container Registry 镜像源）。

## 5. 给外部密钥类 IT 补跳过门槛

以 `TavilySearchToolProviderIT` 为例，现状是直接 `LocalConfig.require("tavily.api-key")`——本地
没配置这个 key 时 `require` 直接抛异常，测试标红，不是优雅跳过。改成类级 `@EnabledIfSystemProperty`
或方法级判断 + `Assumptions.assumeTrue`：

```java
class TavilySearchToolProviderIT {
    @Test
    void fetchesRealSearchResultsFromTavily() {
        Assumptions.assumeTrue(LocalConfig.isPresent("tavily.api-key"),
                "本地未配置 tavily.api-key，跳过（不是失败）");
        String apiKey = LocalConfig.require("tavily.api-key");
        ...
    }
}
```

**先验证**：`LocalConfig` 目前只有 `require(String)`（读不到直接抛异常），没有 `isPresent(String)`
这个判断方法——需要先给 `LocalConfig` 加一个不抛异常的探测方法，抄 `require` 已有的读取逻辑，
只是把"读不到就抛异常"换成"读不到返回 false"。三个需要这个改动的类：`ChartToolProviderIT`、
`TavilySearchToolProviderIT`、`DeepSeekLlmClientLiveIT`（`ImageStrategyIT` 同时依赖
`SharedMySql`，本身已经不进 `integration-ci`，但作为"本地误跑 `-P integration` 又没起 MinIO"
场景的受益者，也应该顺手补上同样的 `Assumptions` 跳过逻辑）。

## Testing Decisions

- 本地跑 `mvn verify -P integration-ci`：在一台没有配置任何 `application-local.yml`（没有
  DeepSeek/Tavily/DashScope key，没有本机 MySQL）、只装了 Docker 的机器上应该全绿——这是这个
  profile 存在的意义，必须实测验证一遍，不能只看代码逻辑推断。
- CI 跑起来后，对比 `target/surefire-reports/` 或 CI 日志里的测试类数量：接入前只有
  `**/*Test.java` 系列，接入后应该能看到 `RedisTaskLockIT`/`AgentTaskManagerCrossInstanceIT`/
  `ToolRateLimiterIT`/`AgentLoopExecutorConfigRedisWiringIT` 四个类的测试结果，且状态是
  PASS 不是 SKIPPED。
- 跳过门槛测试：本地不配置 Tavily key 跑 `-P integration`，`TavilySearchToolProviderIT` 应该
  显示为 SKIPPED（带跳过原因），不是 FAILED；配置了 key 之后应该正常执行并通过。
- 回归验证：`-P integration`（不带 `-ci` 后缀）的现有行为不能变——这个 profile 仍然跑全部
  `**/*IT.java`，本票不缩减它的覆盖范围，只是新增一个更小的子集供 CI 用。

## Out of Scope

- 让 `SharedMySql` 类的 IT 也能在 CI 跑（比如给 GitHub Actions 加一个 MySQL `services:` 常驻
  容器，把 `SharedMySql` 改造成"优先读环境变量，读不到再退回本地约定"）——这是更大的改动，涉及
  这批 IT 的连接方式统一改造，且要重新评估"分钟级 initdb"这个已知的性能代价在 CI 场景下是否
  可接受，本票不做，留给后续单独评估。
- `ChartToolProviderIT`/`TavilySearchToolProviderIT` 之外的其它需要外部服务的 IT 逐个补跳过
  门槛——本票先把最典型的几个改完立个样板，其余的可以复用同一模式陆续补齐，不强制这一票
  全部覆盖完。
- 给 Testcontainers 引入镜像缓存/复用机制（比如 Ryuk 复用、`testcontainers.reuse.enable`）——
  当前 4 个容器的启动开销本身就很小，没有必要在这一票里做额外的性能优化。
