# Ticket 13（Phase 2）：ModelGateway/ToolGateway 切断 Spring AI 泄漏 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 12](refactor-remediation-ticket-12.md)。Blocks [Ticket 14](refactor-remediation-ticket-14.md)。

## 0. 范围边界

5 阶段核心层重构的第 2 步。Ticket 12 冻结了 `AgentRuntimePort` 契约、`LegacyAgentLoopExecutorAdapter`
委托给现有 `AgentLoopExecutor`，但 `AgentLoopExecutor` 内部依然直接依赖 Spring AI 的
`ChatModel`/`ToolCallback`/`Message`/`Flux`。这一票新增 `runtime.model.ModelGateway`、
`runtime.tool` 四接口，以及真正做适配的 `infrastructure.llm.springai.SpringAiModelGateway`，把
`LlmInvoker` 改成依赖 `ModelGateway`。

**这一票只迁移核心层，不强制迁移业务层调用点**：PPT 策略类（`RequirementStrategy`/
`SchemaStrategy`/`OutlineStrategy`/`SearchStrategy`）和 `DeepResearchService` 继续直接持有
`AgentLoopExecutor` 字段——这是 Ticket 16-18 的事。`refactor-blueprint.md` §6 Phase 2 的验收
标准（"`capability.*` 源码中 `rg 'ChatModel|ToolCallback|Message|Flux'` 结果为零或只存在明确
的 adapter 包"）**这一票不强制达成**：只要求核心层新接口可用、有真实 adapter、行为不变。

**先验证（开工前重读，行号可能已漂移）**：`LlmInvoker`（`src/main/java/com/agenttrail/loop/core/LlmInvoker.java`）
当前 76 行，包内可见类（无 `public`），唯一公开方法 `Flux<ChatResponse> streamRound(List<Message>
messages, List<ToolCallback> tools)`；`AgentLoopExecutor` 只有一处构造点
`this.llmInvoker = new LlmInvoker(chatModel)`（`AgentLoopExecutor.java:347`），不是 Spring Bean。

## 1. 现状证据

`LlmInvoker.streamRound`（`LlmInvoker.java:55-58`），唯一直接调用 `ChatModel` 的地方：

```java
Flux<ChatResponse> streamRound(List<Message> messages, List<ToolCallback> tools) {
    return chatModel.stream(new Prompt(messages, buildOptions(tools)))
            .timeout(Mono.delay(ttftTimeout), chunk -> Mono.delay(idleTimeout));
}
```

两段式超时（TTFT + idle）用 Reactor 的双参数 `timeout` 重载：第一个参数管订阅到首个 chunk 的
窗口，第二个是"每个 chunk 之后到下一个 chunk"的窗口生成函数——**这套逻辑迁移后必须原样保留**，
只换依赖，不改触发条件。`buildOptions` 里 `defaultOptions.mutate()` 保留厂商具体 options 子类型
这条注释（`LlmInvoker.java:60-64`）同样要保留，直接 new 通用 `ToolCallingChatOptions` 会在部分
厂商的 `createRequest` 里被硬转类型抛 `ClassCastException`。

`ToolCallback` 泄漏的另外两处（**都要包一层新接口，不重写内部实现**，见 §3）：
`loop/tools/search/ToolCatalog.java`（`Map<String, ToolCallback> tools`，
`of(ToolSearchConfig, List<ToolCallback>, ChatModel)` 静态工厂）、
`loop/tools/search/ToolSearchSession.java`（`discoveredTools()`/`toolSearchCallback()` 均返回
`ToolCallback`）。

业务层已核实存在 Spring AI 直接依赖（`rg -l "import org\.springframework\.ai\."
src/main/java/com/agenttrail/capability`）：`deepresearch/DeepResearchService.java`（`Message`/
`ToolResponseMessage`/`UserMessage`）、`rag/*`（3 个文件）、`file/multimodal/ImageDescriptionService.java`、
`analytics/*`（7 个文件）——这批留给 Ticket 16-18。PPT 策略类构造函数均为 `public XxxStrategy
(AgentLoopExecutor executor)`（装配点 `web/config/PptGenerationConfig.java`），间接暴露在依赖图
里，这一票同样不动。

## 2. `runtime.model`：`ModelGateway` 契约

新增包，不依赖 `reactor-core`/`org.springframework.ai.*`，用 `org.reactivestreams.Publisher`
（Spring Boot 已传递依赖 `reactive-streams`）：

```java
public interface ModelGateway {
    Publisher<ModelChunk> streamRound(ModelRequest request);
}

public record ModelRequest(List<ModelMessage> messages, List<ToolDefinition> tools) {
    public record ModelMessage(Role role, String content) {
        public enum Role { SYSTEM, USER, ASSISTANT, TOOL }
    }
}

/**
 * @param content       正文增量；纯工具调用增量时为空字符串，不是 null
 * @param toolCallDelta 沿用 {@code ToolCallAccumulator} 已有的分片累积语义，只是类型换成不依赖
 *                      Spring AI 的等价值类型
 * @param usage         多数 chunk 为 null，通常只有终局 chunk 附带（迁移时用真实响应验证到达时机，
 *                      不要假设）
 * @param finishReason  仅终局 chunk 非 null
 */
public record ModelChunk(String content, ToolCallDelta toolCallDelta, ModelUsage usage, String finishReason) {
    public record ToolCallDelta(String id, String name, String argumentsFragment) { }
}

public record ModelUsage(Integer promptTokens, Integer completionTokens) { }
```

**已知的简化**：`ModelMessage` 只有 `role`/`content`，没有覆盖 `ToolResponseMessage` 的
`toolCallId`、多模态图片内容等更复杂形态。**先验证**：把 `AgentLoopExecutor.stream()` 现有
`messages` 列表实际会出现的 Spring AI `Message` 子类型过一遍，确认覆盖面——如果 `ToolResponseMessage`
真的会流经这条路径（工具调用轮次的历史消息大概率会），`ModelMessage` 需要补字段，不能因为"两个
字段更简单"丢信息。

## 3. `runtime.tool`：四接口拆分

```java
/** 工具的静态描述——名称/描述/参数 Schema/风险级别，不含"怎么调用"。 */
public record ToolDefinition(String name, String description, Map<String, Object> parameterSchema,
                              RiskLevel riskLevel) {
    public enum RiskLevel { READ_ONLY, WRITE, HIGH_RISK }
}

/** 本轮该给模型看到哪些工具——替代现状"新增 Tool 没有注册机制"的缺口，见 §4。 */
public interface ToolResolver {
    List<ToolDefinition> resolve(ToolResolutionContext context);
}

/** @param roundNumber 部分工具可见性和轮次相关（超出 maxRounds 后不挂任何工具，对应
 *                     {@code AgentLoopExecutor.scheduleRound} 现有的 {@code toolsExhausted}）。 */
public record ToolResolutionContext(String conversationId, int roundNumber) { }

public interface ToolExecutor {
    ToolExecutionResult execute(String toolName, String toolCallId, String argumentsJson);
}

public record ToolExecutionResult(String toolCallId, String resultJson, boolean success) { }

/** 超时/重试/截断/脱敏——现状分散在 {@code ToolCallExecutor}（超时降级、结果截断）和
 * {@code loop.security}（PII 脱敏）里，这里只定形状，不强制拆现有实现。 */
public interface ToolResultPolicy {
    Duration timeout();
    String applyTo(String rawResult);
}
```

**定位是"契约冻结 + 最小可用实现"，不是重写 `ToolCallExecutor`**：`ToolExecutor` 的默认实现可以
直接委托给现有 `ToolCallExecutor`（内部转换 `ToolCallback` 调用）；`ToolResultPolicy` 先给透传
实现（`applyTo` 原样返回，`timeout()` 返回现有 `roundTimeout`）。

## 4. `DefaultToolResolver`：顺带解决"新增 Tool 没有注册机制"

`refactor-blueprint.md` §2.6 指出的缺口——`GrepTool`/`BashTool`/`FileSystemTools`/`TodoWriteTool`
零接入生产，加新工具要动好几处。参照 `PptGenerationService` 已验证过的"恰当的简单"模式
（`PptGenerationService.java:40-48`：`Map<PptState, PptGenerationStrategy>` 建表，缺状态
fail-fast；这份 `List<PptGenerationStrategy>` 是 `PptGenerationConfig` 手动 `@Bean` 逐个注册的，
不是 `@ComponentScan` 自动收集）：

```java
public class DefaultToolResolver implements ToolResolver {

    private final Map<String, ToolDefinition> registry;

    public DefaultToolResolver(List<ToolDefinition> tools) {
        this.registry = tools.stream().collect(Collectors.toMap(ToolDefinition::name, t -> t,
                (first, dup) -> { throw new IllegalStateException("重复注册的工具名: " + first.name()); }));
    }

    @Override
    public List<ToolDefinition> resolve(ToolResolutionContext context) {
        // 第一版全量可见，不区分风险级别/会话状态——按轮次/按会话过滤的现有行为
        // （toolsExhausted/discoveredTools）留给 ToolExecutor 委托 ToolCallExecutor 时原样保留
        return List.copyOf(registry.values());
    }
}
```

不要求真的把 `GrepTool`/`BashTool`/`FileSystemTools`/`TodoWriteTool` 接入生产（是否接入是产品
判断），只要求注册表机制本身可用、有测试覆盖，不是插件加载器/反射扫描那种过度设计。

## 5. `infrastructure.llm.springai.SpringAiModelGateway`：真正的适配层

```java
public class SpringAiModelGateway implements ModelGateway {

    private final ChatModel chatModel;
    private final Duration ttftTimeout;
    private final Duration idleTimeout;

    // 构造函数：默认值同 LlmInvoker 现有的 60s/30s，签名同上，略

    @Override
    public Flux<ModelChunk> streamRound(ModelRequest request) {
        return chatModel.stream(new Prompt(toSpringAiMessages(request.messages()), buildOptions(request.tools())))
                .timeout(Mono.delay(ttftTimeout), chunk -> Mono.delay(idleTimeout))
                .map(this::toModelChunk);
    }

    private List<Message> toSpringAiMessages(List<ModelRequest.ModelMessage> messages) {
        return messages.stream().map(m -> (Message) switch (m.role()) {
            case SYSTEM -> new SystemMessage(m.content());
            case USER -> new UserMessage(m.content());
            case ASSISTANT -> new AssistantMessage(m.content());
            // 先验证：TOOL 需要 toolCallId，ModelMessage 目前没有这个字段，见 §2 的已知简化
            case TOOL -> throw new UnsupportedOperationException("TOOL 角色映射见 Ticket 13 §2/§5");
        }).toList();
    }

    private ModelChunk toModelChunk(ChatResponse response) {
        // 先验证：content/工具调用分片/usage/finishReason 的真实字段映射，对照
        // AgentLoopExecutor.processChunk 现在怎么读这些字段实现，不要凭空编
        throw new UnsupportedOperationException("字段映射见 §5 的先验证说明，实现时补全");
    }

    private ChatOptions buildOptions(List<ToolDefinition> tools) {
        // 先验证：ToolCallingChatOptions.toolCallbacks(...) 收 Spring AI ToolCallback，这里的
        // tools 是 runtime.tool.ToolDefinition，需要一层 ToolDefinition -> ToolCallback 适配，
        // 确认哪种写法和现有 ToolCatalog/ToolSearchSession 衔接最自然
        throw new UnsupportedOperationException("映射见 §5 的先验证说明");
    }
}
```

`toModelChunk`/`buildOptions` 故意留 `throw`，不是凭空编字段映射——**实现时第一步**是打开
`AgentLoopExecutor.processChunk` 和 `ToolCallAccumulator`，把它们现在怎么从 `ChatResponse`/chunk
剥出 content、工具调用分片、finishReason 的逻辑原样搬过来，不重新设计解析方式。

**`LlmInvoker` 改造后**：`class LlmInvoker` 保留、仍是 `loop.core` 包内类，只是把持有的
`ChatModel` 换成 `ModelGateway`，公开签名 `streamRound(List<Message>, List<ToolCallback>) ->
Flux<ChatResponse>` **保守地维持不变**（内部转换成 `ModelRequest` 调 `ModelGateway`，再把
`ModelChunk` 转回 `ChatResponse`）——这样 `AgentLoopExecutor` 零改动。**这是一个需要认清的取舍**：
`LlmInvoker` 本身仍会是"签名对外暴露 Spring AI 类型"的类，只是不再直接依赖 `ChatModel`；彻底把
公开签名也换成 `ModelRequest`/`ModelChunk`，会牵连 `AgentLoopExecutor.processChunk`/`scheduleRound`
所有读 `ChatResponse` 字段的地方，触达 Ticket 14（Runtime 拆 6 模块）的范围，这一票不做。
`capability.*` 的 ArchUnit 规则能不能打开只看 `capability.*` 包内是否还 import Spring AI 类型，
不要求 `loop.core` 内部实现细节也做到零引用。

## 6. `ToolCatalog`/`ToolSearchSession` 的处理方式

不重写内部实现——外面包一层 `runtime.tool` 适配（比如一个 `SpringAiToolResolver` 内部持有
`ToolCatalog`，`resolve()` 把 `ToolCallback` 转成 `ToolDefinition`），"预建检索索引"/"会话级
已发现工具集合"这些实现细节原样不动。

## Testing Decisions

- `SpringAiModelGatewayTest`：复用已有测试替身 `loop.core.support.ScriptedChatModel`，覆盖流式
  分片拼接、usage 映射（多数 chunk 为 null）、TTFT 超时、idle 超时（两种脚本分别验证，和
  `LlmInvokerTest` 现有验证方式一致，包括用 `doOnCancel` 探针验证真正取消了上游订阅）、
  `ChatModel.stream(...)` 直接抛异常时正确传播 onError
- `LlmInvoker` 改造后的回归测试：复用同一份 `ScriptedChatModel` 脚本，对比改造前后（或改造后
  `LlmInvoker`+`SpringAiModelGateway` 与手工计算的预期输出）产出的 `ChatResponse` 序列在文本
  内容/工具调用分片/完成信号上一致——手法参照 Ticket 12 `LegacyAgentLoopExecutorAdapterTest`
  的"两条路径跑同一份脚本对比产出"
- `DefaultToolResolverTest`：正常注册表解析；重复工具名在构造时 fail-fast（`IllegalStateException`）
- ArchUnit：这一票完成后 `capabilityShouldNotDependOnSpringAi` 这条 `@Disabled` 规则**保持禁用**
  （业务层调用点不动，要到 Ticket 16-18 才能打开）；除非实现时发现业务层顺手也干净了，不要为了
  "看起来完成度更高"提前打开一条实际还有违规的规则

## Out of Scope

- 业务层策略类/`DeepResearchService` 迁移到依赖 `ModelGateway`/`ToolResolver`——Ticket 16-18
- `ToolCatalog`/`ToolSearchSession` 现有实现的重写——只包一层新接口
- `GrepTool`/`BashTool`/`FileSystemTools`/`TodoWriteTool` 真正接入生产——是否接入是产品判断
- `ToolResultPolicy` 的真实超时/重试/截断/脱敏实现——先给透传实现占住接口形状
- `LlmInvoker`/`AgentLoopExecutor` 公开签名改成 `ModelRequest`/`ModelChunk`——见 §5 保守方案，
  彻底切断留给 Ticket 14
