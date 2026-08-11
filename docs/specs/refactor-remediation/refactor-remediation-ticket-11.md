# Ticket 11（Phase 0）：架构护栏与基线 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocks [Ticket 12](refactor-remediation-ticket-12.md)。

## 0. 范围边界

这一票**不改动任何业务逻辑**，只建立"改了会立刻发现"的护栏，是后面 Phase 1-4 的安全网。三块
彼此独立，可以任选顺序实现：① ArchUnit 依赖方向基线测试；② 生产装配启动时打印 Runtime Profile
摘要；③ 五个入口的 HTTP 契约快照测试。Out of Scope 见文末——不改任何生产代码逻辑，只加测试和
一行启动日志。

## 1. ArchUnit 依赖方向基线

**先验证**：`pom.xml` 目前没有任何 `archunit` 坐标（已核实——全文搜索 `archunit` 零命中），
需要新增 test-scope 依赖：

```xml
<dependency>
    <groupId>com.tngtech.archunit</groupId>
    <artifactId>archunit-junit5</artifactId>
    <scope>test</scope>
</dependency>
```

版本交给 `spring-boot-dependencies` BOM 管不到（ArchUnit 不在 Spring Boot BOM 里），需要显式给
一个版本号——写票时 ArchUnit 最新稳定线是 1.3.x，**开工前先查一次 Maven Central 确认当前可用的
最新版本**，不要照抄这个数字。

新增测试类 `com.agenttrail.architecture.ArchitectureBaselineTest`（新包 `src/test/java/com/agenttrail/architecture/`），
用 `ClassFileImporter` 扫描 `com.agenttrail` 全部产物类。**这一票的核心不是"让规则全绿"，是
"先量化现状"**——写一批目标依赖方向规则（对齐 `refactor-blueprint.md` §5 的目标依赖图），
现在多半是违反的，第一版全部标 `@Disabled`，`disabledReason` 里写清楚现状违规点：

```java
package com.agenttrail.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

class ArchitectureBaselineTest {

    private final com.tngtech.archunit.core.domain.JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.agenttrail");

    /**
     * 目标态规则（refactor-blueprint.md §5）：capability.* 不依赖 web.dto——当前 PPT/DeepResearch
     * 的 Controller 和 Service 之间还没有独立于 web 层 DTO 的应用层协议，这条现在是违反的。
     * TODO(Phase 5-9，Ticket 16-18)：capability.* 迁到自己的请求/响应类型后再打开这条规则。
     */
    @Test
    @Disabled("现状违规：capability.deepresearch/capability.ppt 直接使用 web.dto 里的请求/响应类型，"
            + "见 DeepResearchService/PptGenerationService 的方法签名；Ticket 16-18 迁移后再打开")
    void capabilityShouldNotDependOnWebDto() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.agenttrail.capability..")
                .should().dependOnClassesThat().resideInAPackage("com.agenttrail.web.dto..");
        rule.check(classes);
    }

    /**
     * 目标态规则：loop.core（未来的 runtime.engine）不依赖 capability.*——当前
     * AgentLoopExecutor 直接 import capability.file.FilePromptFormatter/FileStore
     * （AgentLoopExecutor.java 第 6-7 行），这条现在是违反的。
     * TODO(Phase 3，Ticket 14)：文件问答注入逻辑挪到 ContextAssembler/独立中间件后再打开。
     */
    @Test
    @Disabled("现状违规：loop.core.AgentLoopExecutor 直接 import capability.file.FilePromptFormatter"
            + "/capability.file.FileStore，见该类第 6-7 行 import；Ticket 14 拆 ContextAssembler 后再打开")
    void loopCoreShouldNotDependOnCapability() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.agenttrail.loop.core..")
                .should().dependOnClassesThat().resideInAPackage("com.agenttrail.capability..");
        rule.check(classes);
    }

    /**
     * 目标态规则：Spring AI 类型（ChatModel/ToolCallback/Message/Flux 等）不出现在 capability.* 的
     * 公开签名里——当前 PPT 策略类（RequirementStrategy/OutlineStrategy/SchemaStrategy）和
     * DeepResearchService 都直接持有 loop.core.AgentLoopExecutor 字段，间接暴露在依赖图里；
     * 更直接地，capability 包内也有直接 import org.springframework.ai.* 的地方。
     * TODO(Phase 2，Ticket 13)：ModelGateway/ToolGateway 落地后再打开。
     */
    @Test
    @Disabled("现状违规：见 Ticket 13 范围描述——capability.* 里 rg 'ChatModel|ToolCallback|Message|Flux' "
            + "非零命中；Ticket 13 落地 ModelGateway/ToolGateway 后再打开")
    void capabilityShouldNotDependOnSpringAi() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.agenttrail.capability..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "org.springframework.ai..", "reactor..");
        rule.check(classes);
    }

    /**
     * 现在就应该成立、不需要 @Disabled 的规则：Controller 不直接依赖 JDBC/DataSource——
     * 这条如果一开始就是绿的，留着当"以后别退步"的护栏用，不属于"现状基线"。
     * 先验证：开工前实际跑一次，如果发现现在也是红的，把它也降级成 @Disabled 并记录违规点，
     * 不要为了让它看起来"干净"就削弱断言范围。
     */
    @Test
    void controllersShouldNotDependOnJdbcDirectly() {
        ArchRule rule = ArchRuleDefinition.noClasses().that().resideInAPackage("com.agenttrail.web.controller..")
                .should().dependOnClassesThat().resideInAnyPackage("javax.sql..", "java.sql..");
        rule.check(classes);
    }
}
```

**这份 TODO 清单本身就是这一票的交付物之一**——后续 Ticket 13/14/16-18 在验收标准里都应该包含
"把对应的 `@Disabled` 规则打开"这一步，不需要在这一票里预判所有 Phase 会新增哪些规则，先按
`refactor-blueprint.md` §5/§7 已经列出的方向起步即可（`runtime.engine` 不依赖 `capability.*`；
`capability.*` 不依赖 `interfaces.*`；`interfaces.*` 不依赖 infrastructure 实现类；`capability.*`
不依赖 `org.springframework.ai.*`；Repository 实现只出现在 `infrastructure.*`；Controller 只调用
application service）——当前包结构下（`loop`/`capability`/`web` 三段式，还没有 `runtime`/
`infrastructure`/`interfaces` 这些目标态包名）大部分规则要么改用现有包名描述、要么直接标
`@Disabled` 附带"等 Ticket N 建好目标包结构后再启用"的说明，不要为了让规则"能写"而提前建空壳包。

## 2. 生产装配启动摘要日志

**接入点**：`AgentLoopExecutorConfig.agentLoopExecutorFactory(...)`（`src/main/java/com/agenttrail/web/config/AgentLoopExecutorConfig.java`
第 257-287 行）——这个方法已经把所有可选机制（`pauseConfig`/`traceStore`/`promptInjectionGuard`/
`piiMasker`/`toolRateLimiter`/`skillManager`/`memoryStore`/`meterRegistry` 等）通过
`ObjectProvider.getIfAvailable()` 解析成了 null 或非 null，是打印摘要的天然位置——不需要新建
一个专门的摘要收集器，直接在这个 `@Bean` 方法构造完 `AgentLoopExecutorFactory` 之后加一行日志。

```java
@Bean
public AgentLoopExecutorFactory agentLoopExecutorFactory(/* 现有参数不变 */) {
    List<RegisteredModel> models = List.of(/* 不变 */);
    AgentLoopExecutorFactory factory = new AgentLoopExecutorFactory(/* 不变 */);
    logRuntimeProfileSummary(models, "qwen-plus", tavilySearchToolProvider, chartToolProvider,
            pauseConfig, memoryStoreProvider.getIfAvailable() != null, traceStore != null,
            meterRegistryProvider.getIfAvailable() != null);
    return factory;
}

private static final Logger log = LoggerFactory.getLogger(AgentLoopExecutorConfig.class);

private void logRuntimeProfileSummary(List<RegisteredModel> models, String defaultModelId,
        TavilySearchToolProvider webSearch, ChartToolProvider chart, PauseConfig pauseConfig,
        boolean memoryEnabled, boolean traceEnabled, boolean metricsEnabled) {
    List<String> tools = new ArrayList<>();
    if (webSearch != null) tools.add("web-search");
    if (chart != null) tools.add("chart");
    log.info("agentLoopExecutorFactory 装配完成：profile=chat-default model={} models={} tools={} "
                    + "pause={} memory={} trace={} metrics={}",
            defaultModelId, models.stream().map(RegisteredModel::id).toList(), tools,
            pauseConfig != null, memoryEnabled, traceEnabled, metricsEnabled);
}
```

格式参考 `refactor-blueprint.md` §1.8 给的例子：`profile=chat-default model=deepseek-chat
tools=[web-search,chart] pause=false memory=false trace=true persistence=jdbc`——**先验证**：
这个例子里 `model=deepseek-chat` 是文档给的示例值，当前生产装配的默认模型实际是 `qwen-plus`
（见 `agentLoopExecutorFactory` 方法体第 282 行 `new AgentLoopExecutorFactory(models, "qwen-plus", ...)`），
不要照抄文档示例值当作要断言的真实默认模型；日志字段可以按上面代码框架的取舍来（比如
`persistence` 字段——`turnPersistenceHook` 目前只有 `JdbcSessionStore` 一种实现，不像
`pause`/`memory`/`trace` 那样有"启用/不启用"的二态，是否要在摘要里单列一个恒定值字段，
或者干脆不列，两种做法都合理，写票时不强制）。

**为什么放在这一层而不是 `AgentLoopExecutor`/`AgentLoopExecutorFactory` 内部**：这两个类目前是
纯粹的执行器/工厂，不持有"哪些机制被启用"这份跨执行器变体（plain/webSearch/analytics/chart）的
全局视图；`AgentLoopExecutorConfig` 已经是唯一同时看到全部治理依赖装配结果的地方，加一行日志
不需要改动 `AgentLoopExecutor`/`AgentLoopExecutorFactory` 任何一行代码，符合"这一票不碰业务逻辑"
的范围边界。

**验收方式**：本地启动一次应用，在启动日志里用肉眼/`grep`能直接找到这一行，不需要翻源码猜生产
环境实际启用了哪些机制。

## 3. HTTP 契约快照测试

**先验证，这一步很重要**：Ticket 09 的原始设计假设"用现有 MockMvc/WebTestClient 风格照抄项目
已有测试的写法"——实地核实后发现这个假设不成立。全仓库搜索 `MockMvc`/`WebTestClient` 命中的
文件里没有一个是真正对 Controller 发起 HTTP 请求的用法，`web/controller` 下现有的四个测试
（`AgentControllerTest`/`CapabilityControllersTest`/`DeepResearchTaskRegistryTest`/
`FileUploadControllerTest`）全部走同一种模式：**mock 依赖 → 直接 `new` 出 Controller → 直接调用
方法 → 断言返回的 DTO**，`SkillControllerTest` 的类注释把理由写得很明确——"权限校验是方法级 AOP
注解，脱离 Spring 容器直接调用不会触发，这层测试只验证 controller 把请求正确转译成了对
XxxManager 的调用"。这一票的契约快照测试**照抄这个既有约定，不引入 MockMvc**——引入一种全新的
测试风格只为了五个快照测试，会和仓库里其余几十个 Controller/Service 测试的写法割裂，且要额外
解决 Sa-Token 拦截器在无容器场景下怎么绕过的问题，本身就是这套既有约定已经解决掉的问题。

**登录态怎么处理**：四个入口（`DeepResearchController`/`PptGenerationController`/
`FileUploadController`）内部用 `currentUserId()` helper 吞掉未登录时 `StpUtil` 抛出的
`RuntimeException`，直接调用不需要模拟登录态就能测最基本路径；但要覆盖"已登录用户"这条路径
（大多数真实流量都是已登录），或者测 `AgentLoopController`（它的 `chat`/`approve`/`stop` 等方法
**没有**做类似的防御式 try-catch，直接调用 `StpUtil.getLoginIdAsString()`，脱离真实 HTTP 上下文
会直接抛异常），需要用 `FileUploadControllerTest.java` 第 171-175 行已经建立的 `mockStatic` 模式：

```java
private static MockedStatic<StpUtil> loggedInAs(String userId) {
    MockedStatic<StpUtil> stp = mockStatic(StpUtil.class);
    stp.when(StpUtil::isLogin).thenReturn(true);
    stp.when(StpUtil::getLoginIdAsString).thenReturn(userId);
    return stp;
}
```

五个入口的快照测试新增在 `src/test/java/com/agenttrail/web/controller/HttpContractSnapshotTest.java`
（或者拆到各自现有测试类里新增方法，两种组织方式都可以，取决于实现时哪种读起来更清楚）：

| 入口 | 现有测试覆盖 | 这一票要新增的快照点 |
|---|---|---|
| `POST /agent/chat`（`AgentController`） | `AgentControllerTest` 已覆盖基本路径 | 固定 `AgentChatRequest{message}` → `AgentChatResponse{answer}` 的字段形状不再新增字段/改名 |
| `POST /agent/v1/chat`（`AgentLoopController`） | **无**——`AgentLoopController` 目前没有任何测试文件 | 用 `mockStatic(StpUtil.class)` + mock 一个 `AgentLoopExecutor`（`AgentLoopExecutorFactory.forModelWithCharts` 返回它），`stream()` 返回预置的 `Flux.just(AgentStreamEvent.AgentStart, Text, Complete)`；断言 `chat()` 返回的 `Flux<ServerSentEvent<...>>` 里每个事件的 `event()` 名字等于 `AgentStreamEvent` 各 record 的 simple name（`asSse` 方法第 141-145 行的映射规则），顺序和数量与输入一致 |
| `POST /agent/v1/deepresearch`（`DeepResearchController`） | `CapabilityControllersTest` 已覆盖基本路径 | 固定 `DeepResearchTaskResponse.running(taskId)` 的初始返回形状（`status=RUNNING`，`report=null`）——这是任务提交那一刻真正对外可见的响应契约，不用等后台任务跑完 |
| `POST /agent/v1/ppt/create`（`PptGenerationController`） | `CapabilityControllersTest` 已覆盖 | 固定 `PptGenerationResponse{taskId, status, errorMsg, downloadUrl}` 四字段形状；`downloadUrl` 在非 SUCCESS 状态下必须是 `null`（`toResponse` 方法第 171-175 行的条件逻辑） |
| `POST /agent/v1/files`（`FileUploadController`） | `FileUploadControllerTest` 已覆盖 | 固定 `FileUploadResponse{fileId, fileName, sizeBytes, parsedTextLength, routedToRag}` 五字段形状 |

**快照怎么做才算"快照"，不是普通功能测试**：普通测试断言"这个值等于我预期的值"；契约快照测试
额外断言"这个 DTO 只有这几个字段，没有多也没有少"——用 Jackson 把响应对象序列化成
`ObjectMapper.valueToTree(response)`，断言 `JsonNode.properties()`（或 `fieldNames()`）的集合
和一份写死的字段名清单完全相等。这样将来有人在 DTO 上加/删/改字段名，测试会显式失败并要求
"你是不是要顺手改一下这份契约清单"，而不是悄悄通过——这正是"重构没有意外改变对外行为"这个
诉求要的效果。

```java
@Test
void deepResearchTaskResponseShapeIsFrozen() {
    DeepResearchTaskResponse response = DeepResearchTaskResponse.running(9L);
    JsonNode node = new ObjectMapper().valueToTree(response);
    assertThat(fieldNamesOf(node)).containsExactlyInAnyOrder("taskId", "status", "report");
    assertThat(node.get("status").asText()).isEqualTo("RUNNING");
    assertThat(node.get("report").isNull()).isTrue();
}
```

**先验证**：上面表格里每个 DTO 的实际字段名以 `src/main/java/com/agenttrail/web/dto/` 下对应
record 的真实声明为准——写票时没有逐个打开每个 DTO 文件核对字段名拼写，实现时第一步就是打开
对应文件确认，不要凭记忆里的名字直接写断言。

## Testing Decisions

- ArchUnit 基线测试本身就是这一票的测试交付物，不需要额外的"测试的测试"；验收标准是"能跑起来、
  `@Disabled` 规则的现状违规描述和代码里的真实证据（文件+行号）对得上"，不是"全部变绿"
- 启动日志：本地 `mvn spring-boot:run`（或者等价的 IDE 启动）一次，人工确认摘要行能在控制台里
  找到；不需要为一行日志专门写单元测试断言 Logger 输出，成本不成比例
- 契约快照测试：五个入口各自的字段形状测试独立运行，互不依赖；`AgentLoopController` 的 SSE
  快照额外验证事件顺序（`AgentStart` → `Text` → `Complete`，而不只是"数量对"）

## Out of Scope

- ArchUnit 规则的全绿化——这是 Ticket 13/14/16-18 各自验收标准的一部分，不是这一票要做的事
- Runtime Profile 摘要日志之外的任何生产代码改动——不新增/修改任何治理机制本身的行为
- 除五个点名入口之外的其它 Controller（比如 `TraceAuditController`/`GoldenCaseController`）的
  契约快照——那些不在 `refactor-blueprint.md` §6 Phase 0 列的四个入口范围内，且 Ticket 01 已经
  在处理它们的权限校验问题，交叉改动容易互相踩脚
