# Ticket 08：Redis/PgVector/MinIO/DashScope HealthIndicator — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：为 Redis、PgVector、MinIO、DashScope 四个外部依赖各写一个
`org.springframework.boot.actuate.health.HealthIndicator` 实现，注册到
`/actuator/health` 下对应的 component。不涉及 CORS 配置（`refactor-blueprint.md` §3.2 提到的
另一个缺口，`refactor-remediation.md` 的 Out of Scope 已明确排除）。

证据见 `refactor-blueprint.md` §3.2："没有任何自定义 `HealthIndicator`
（`grep -rln HealthIndicator` 零命中）——`/actuator/health` 只反映 Spring Boot 自动探测的
DataSource/磁盘检查，对 Redis/PgVector/MinIO/DashScope 的可达性完全没有探针"。复核确认仓库
当前确实没有任何文件实现该接口。

## 1. 现有可复用的 Bean（先验证结论）

逐个依赖读了现有配置类，四个依赖的复用基础不对等，需要分别处理：

| 依赖 | 现有 Bean | 位置 | 复用方式 |
|---|---|---|---|
| Redis | `RedissonClient`（条件装配，默认不存在） | `RedisConfig.java`（`loop/task/`） | `ObjectProvider<RedissonClient>.getIfAvailable()`，同 `AgentLoopExecutorConfig.agentTaskManager`（`web/config/AgentLoopExecutorConfig.java:96-100`）已验证过的降级写法 |
| PgVector | `PgVectorDataSource`（`RagConfig.PgVectorDataSource` 内部类，非 Spring `DataSource` 类型，故意不注册成 `DataSource` bean 避免和主 MySQL 冲突，见类注释） | `RagConfig.java`（`web/config/`） | 直接注入 `RagConfig.PgVectorDataSource`（当前是 package-private 静态内部类，**先验证**：`HealthIndicator` 实现类如果不在 `web.config` 包内，需要把这个内部类改成至少包外可见，或者改成注入 `VectorStore`/单独暴露一个 `JdbcTemplate` bean——任选其一，取决于把 `HealthIndicator` 放在哪个包） |
| MinIO | **没有独立的 `MinioClient` bean**——`MinioClient` 实例被私有持有在 `MinioPptImageStore` 内部（`MinioPptImageStore.java:29,38-39`），`PptGenerationConfig.pptImageStore(...)`（`web/config/PptGenerationConfig.java:148-156`）只把构造好的 `MinioPptImageStore` 暴露成 `PptImageStore` 接口类型，接口本身没有"ping 一下 MinIO"的方法 | `PptGenerationConfig.java`/`MinioPptImageStore.java` | 需要新增一个独立的 `MinioClient` bean（复用同一份 `agenttrail.minio.*` 配置），或者给 `MinioPptImageStore`/`PptImageStore` 补一个健康检查方法——**不能重新建一份连接配置**，两种做法都要确保 endpoint/access-key/secret-key 与 `PptGenerationConfig.pptImageStore(...)` 使用的是同一组 `@Value` |
| DashScope | 没有常驻 client bean——`DashScopeImageClient`（`capability/ppt/image/`）是 `PptGenerationConfig.pptTextToImageClient(...)`（`web/config/PptGenerationConfig.java:133-141`）里构造的 `TextToImageClient` 接口实例，`apiKey` 来自 `${spring.ai.openai.api-key}`，和主对话模型共用同一个 DashScope Key（类注释已说明） | `PptGenerationConfig.java` | 按票面要求做"配置是否存在"级别的浅层检查，不发起真实模型调用 |

## 2. Redis HealthIndicator

```java
package com.agenttrail.web.health;

import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Redis 默认不启用（{@code agenttrail.redis.enabled=false}，见 RedisConfig 类注释）——这种情况
 * 下不该报 DOWN，因为它本来就不该被依赖，参照 AgentTaskManager/ToolRateLimiter 已经验证过的
 * "ObjectProvider.getIfAvailable() 拿不到就是不启用，不是故障"这套语义。
 */
@Component("redis")
public class RedisHealthIndicator implements HealthIndicator {

    private final ObjectProvider<RedissonClient> redissonProvider;

    public RedisHealthIndicator(ObjectProvider<RedissonClient> redissonProvider) {
        this.redissonProvider = redissonProvider;
    }

    @Override
    public Health health() {
        RedissonClient redisson = redissonProvider.getIfAvailable();
        if (redisson == null) {
            return Health.unknown().withDetail("reason", "agenttrail.redis.enabled=false，未装配").build();
        }
        try {
            // isShutdown()/getNodesGroup() 不发真实网络请求；用一个轻量 key 的 exists 调用
            // 才是真正验证可达性——bucket 的 isExists() 会发一次 Redis 命令
            boolean reachable = redisson.getBucket("agenttrail:health:probe").isExistsAsync()
                    .toCompletableFuture().get(2, java.util.concurrent.TimeUnit.SECONDS);
            return Health.up().build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
```

**先验证**：`RedissonClient` 是否有更轻量的"ping"原语（比如 `redisson.getNodesGroup()` 或者
底层 `RedisClient` 的 `PING` 命令），避免每次健康检查都真的发一条 `EXISTS` 命令到 Redis——
如果 Redisson API 里有专门的健康探测方法，优先用那个；上面的 `isExistsAsync()` 是保证能拿到
真实可达性信号的兜底方案，不是唯一实现方式。**2 秒超时是硬性要求**（见第 5 节）。

## 3. PgVector HealthIndicator

```java
@Component("pgvector")
public class PgVectorHealthIndicator implements HealthIndicator {

    private final JdbcTemplate jdbcTemplate;   // 从 RagConfig 暴露出来的 PgVector JdbcTemplate

    public PgVectorHealthIndicator(@Qualifier("pgVectorJdbcTemplate") JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Health health() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Health.up().build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
```

PgVector 目前没有"未启用"的开关（`RagConfig.java` 的 `pgVectorDataSource` bean 是无条件装配的，
`@Value` 直接读 `agenttrail.pgvector.url` 等必填配置，没有配就启动失败）——**先验证**：确认
这一点属实（`RagConfig.java` 没有 `@ConditionalOnProperty`），如果属实，PgVector
HealthIndicator 不需要处理"未启用"分支，因为应用能启动就代表这个依赖是必需的，只有"可达/不
可达"两态，比 Redis 简单。需要新增一个 `pgVectorJdbcTemplate` bean（`RagConfig.java` 里加一个
`@Bean` 把 `PgVectorDataSource.jdbcTemplate()` 暴露出去，不改变现有 `PgVectorDataSource` 内部
类的可见性范围）。

## 4. MinIO HealthIndicator

```java
@Component("minio")
public class MinioHealthIndicator implements HealthIndicator {

    private final MinioClient minioClient;
    private final String bucket;   // agenttrail.ppt.image.minio-bucket，复用同一份配置确认 bucket 可达

    @Override
    public Health health() {
        try {
            boolean exists = minioClient.bucketExists(
                    BucketExistsArgs.builder().bucket(bucket).build());
            return exists ? Health.up().build()
                    : Health.down().withDetail("reason", "bucket 不存在: " + bucket).build();
        } catch (Exception unreachable) {
            return Health.down(unreachable).build();
        }
    }
}
```

**需要在 `PptGenerationConfig.java` 里补一个独立的 `MinioClient` bean**（把
`MinioPptImageStore.java:39` 里 `MinioClient.builder().endpoint(endpoint).credentials(accessKey,
secretKey).build()` 这行的构造逻辑提出来，改成先造一个 `MinioClient` bean，再把这个 bean 注入
`MinioPptImageStore`——`MinioPptImageStore` 的构造函数目前直接接收 endpoint/accessKey/
secretKey 字符串自己造 client（`MinioPptImageStore.java:38-44`），需要改成接收
`MinioClient`。**先验证**：`MinioPptImageStore` 是否有其它调用方/测试直接用字符串构造函数
（`grep -rn "new MinioPptImageStore"`），确认改造签名不会破坏现有测试，或者保留字符串构造函数
同时加一个 `MinioClient` 构造函数重载，两者都能接受，取决于哪个改动面更小）。

bucket 检查用 `bucketExists`——比"随便读一个 key"更轻量，且不需要 bucket 里真的有数据。

## 5. DashScope HealthIndicator（浅层检查）

```java
@Component("dashscope")
public class DashScopeHealthIndicator implements HealthIndicator {

    private final String apiKey;   // ${spring.ai.openai.api-key}，同 PptGenerationConfig.pptTextToImageClient 复用的同一份配置

    @Override
    public Health health() {
        return (apiKey == null || apiKey.isBlank())
                ? Health.down().withDetail("reason", "spring.ai.openai.api-key 未配置").build()
                : Health.up().withDetail("check", "配置存在性检查，未发起真实模型调用").build();
    }
}
```

按票面要求："如果没有轻量的健康检查 API，说明这一项做成'配置是否存在'级别的浅层检查即可，
不要为了健康检查专门发一次真实的模型调用产生成本"——DashScope 官方文档（
`DashScopeImageClient.java` 类注释已经核实过接口契约来源）没有专门的健康探测端点，这里不
调用 `qwen-image-plus` 或任何 chat 接口，只检查 `spring.ai.openai.api-key` 是否非空。这个
检查粒度低（配了 key 不代表 key 真的有效/账户有余额），但符合票面"不产生成本"的明确约束。

## 6. 超时与并发保护

Spring Boot Actuator 默认对 `HealthIndicator` **没有内建超时**——单个检查阻塞会拖慢整个
`/actuator/health` 的响应。四个 HealthIndicator 各自的检查方式里，Redis/PgVector/MinIO 都是
真实网络调用，必须显式设超时：

- Redis：`RedissonClient` 异步方法 + `.get(2, TimeUnit.SECONDS)`（如第 2 节示例）。
- PgVector：`JdbcTemplate` 本身走的是 `HikariDataSource`（`RagConfig.java:38-47`
  `maximum-pool-size` 默认 4），Hikari 有 `connectionTimeout` 默认 30 秒——**先验证**：
  `RagConfig.pgVectorDataSource(...)` 目前没有显式设置 `connectionTimeout`（对照
  `refactor-blueprint.md` §1.5 提到"主 HikariCP 连接池从未显式调参"这个已知问题在 pgvector
  池上不存在，pgvector 池本身调过参但没调 `connectionTimeout`），30 秒对健康检查来说太长，
  健康检查专用的 `JdbcTemplate`（或者单独一个 `queryTimeout`）应该设一个更短的值（比如 2-3
  秒），可以用 `JdbcTemplate.setQueryTimeout(...)`，不需要动主 pgvector 连接池本身的
  `connectionTimeout`（那会影响正常业务查询）。
- MinIO：MinIO Java SDK 的 `MinioClient.builder()` 支持 `.httpClient(OkHttpClient)`
  自定义超时——**先验证**：如果新增的 `MinioClient` bean 和 `MinioPptImageStore` 内部原有的
  client 复用同一个实例（推荐这样做，避免两份连接池），健康检查用到的超时应该是这个共享
  client 本身的超时设置，不要为健康检查单独再造一个更短超时的第二个 `MinioClient` 实例
  （会有两份连接池，违反"复用现有 Bean"的初衷）；如果发现共享超时对正常业务请求太短、对
  健康检查又太长，两者诉求冲突，记录成 Further Notes，这一票先接受共用同一个超时值。
- DashScope：无网络调用，天然不需要超时。

## 7. Testing Decisions

- 每个依赖分别在"正常可达"和"故意断开/配错"两种场景下验证 `/actuator/health` 返回对应的
  UP/DOWN——Redis/PgVector/MinIO 用 Testcontainers（`refactor-blueprint.md` §4.4 提到的
  Testcontainers 类集成测试已经是项目里 Redis 相关测试的标准做法，见 `RedisTaskLockIT.java`）
  起一个真实容器验证 UP，再停掉容器或改错端口验证 DOWN。
- Redis 额外验证"未启用"（`agenttrail.redis.enabled=false`，默认配置）时返回 UP 或 UNKNOWN
  而不是 DOWN。
- 验证单个依赖检查超时不会拖慢整体响应：可以起一个模拟的慢响应服务（或者对某个依赖故意配置
  一个不可达但不会立即拒绝连接的地址，比如一个黑洞 IP），断言 `/actuator/health` 整体响应
  时间在可控范围内（略高于单个检查的超时阈值，而不是等到 TCP 默认超时，一般是几十秒到分钟
  级）。
- DashScope 分别验证 `api-key` 配置存在/为空两种场景下的 UP/DOWN，不需要真实调用外部 API，
  也不需要 mock 网络层。

## Out of Scope

- CORS 配置（`refactor-blueprint.md` §3.2 提到的另一个缺口，与 HealthIndicator 无关，
  `refactor-remediation.md` 顶层 Spec 未单独立票）。
- DashScope 健康检查覆盖到"key 有效性/账户余额"这类需要真实调用的深层检查。
- `management.endpoint.health.show-details` 等 Actuator 展示细粒度配置——这一票只保证四个
  component 的 UP/DOWN 语义正确，不涉及暴露给哪些角色看的权限收敛（如果需要限制谁能看到
  health 详情，是另一个话题，参照 `refactor-blueprint.md` §4.1 权限校验类问题）。
