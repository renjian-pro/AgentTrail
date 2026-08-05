# Ticket 1／9：Hooks 骨架（六个拦截点）+ 工具风险分级 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。这份文档比该 spec 更细，
> 精确到文件路径、精确签名、精确调用点，专门写给按步骤实现用的。不确定的地方标了「先验证」。
>
> Ticket 2、9 都 `Blocked by` 这一票（2 依赖工具风险分级的产出，9 依赖 `PreToolUseHook` 拦截点）。
> Ticket 3/4/5/6/7/8 不依赖这一票，可以并行开工。

## 0. 范围边界（先划清楚，防止越界）

**这一票只做**：
- 定义六个 Hook 接口（`SessionStart`/`PreToolUse`/`PostToolUse`/`Budget`/`OnError`/`SessionEnd`），
  全部是**纯观察型**（void 返回值，不影响任何现有控制流）
- 把这六个拦截点接入 `AgentLoopExecutor` 的正确位置，默认 Hook 列表为空，**这一票落地后现有行为
  必须零变化**（除了多几次空列表遍历的开销）
- 定义工具风险分级（`ToolRiskLevel` 枚举 + `ToolRiskRegistry`），并用当前代码库里真实存在的
  工具名把它填好

**这一票不做**（不要顺手实现，留给后面的票）：
- 用 `ToolRiskRegistry` 驱动人工审批（Ticket 2）——这一票的 Registry 只是"分类结果放在那里"，
  没有任何地方读它做决策
- 任何具体 Hook 实现（审计哈希链写入、Micrometer 埋点、限流计数器）——那些分别是 Ticket 4/5/9
  的范围，这一票只提供"能挂 Hook 实现"的骨架
- DeepResearch/PPT 取消端点——Ticket 3 的范围，和这一票的 Hook 骨架没有依赖关系

看到"顺手把 XX 也做了"的冲动时，先检查是不是在这两条里，不在就不做。

## 1. 验收标准（机械可核对，不满足不能算完成）

- [ ] `mvn test` 全绿，不新增任何 Testcontainers 依赖（这一票不碰数据库/Redis）
- [ ] 新增一个实现全部六个接口、每次调用往一个 `List` 里记一条"被调用过"标记的测试专用 Hook
  （或六个独立的测试 Hook），挂到一个 `AgentLoopExecutor` 实例上，跑一次带工具调用的完整对话，
  断言六个拦截点**各自在预期的时机被调用了预期的次数**（见第 5 节每个拦截点的验证要点）
- [ ] 不挂任何 Hook（`AgentHooks.EMPTY`，也是 `Builder` 不调用 `.hooks(...)` 时的默认值）时，
  现有的 `AgentLoopExecutorTest`/`AgentLoopExecutorToolCallTest`/`AgentLoopExecutorPauseResumeTest`
  等已有测试全部不受影响、原样通过——这是"零行为变化"的机械验证
- [ ] `ToolRiskRegistry.defaults()` 对当前代码库里真实存在的工具名返回正确分级（见第 4 节表格），
  对不认识的工具名返回 `READ_ONLY`（fail-open，理由见第 4 节说明）
- [ ] `AgentLoopExecutorFactory` 的 `buildExecutor` 和 `forAnalytics` 两处 `Builder` 装配都接上了
  同一个共享 `AgentHooks` 实例（这一票默认是空的，Ticket 2/4/5/9 会往里面加真正的实现）

## 2. 开工前必须验证的技术假设

**这一票技术上没有不确定性**——不引入新依赖，不碰数据库，纯粹是新增几个小类型 + 在已有方法里插入
几行调用。唯一要在动手前确认的是：`AgentLoopExecutor` 当前有 19 个测试文件直接用最长的那个
telescoping 构造函数（`new AgentLoopExecutor(chatModel, tools, maxRounds, taskManager, ...,
maxConsecutiveToolFailures)`），**这一票绝对不能改动这个公开构造函数的签名**，否则这 19 个文件
全部编译失败。处理方式见第 3 节。

## 3. 新增包：`com.agenttrail.loop.hook`

对齐 `loop.pause`/`loop.trace`/`loop.task` 这些既有的同级包风格——一个 package 装一整套协作机制。

### 3.1 `HookContext.java`

```java
package com.agenttrail.loop.hook;

/**
 * Hook 实现能看到的运行时上下文快照，不直接暴露内部的 {@code RunContext}
 * （包私有、挂着 Reactor Sink 等内部状态，Hook 实现不应该碰这些）。
 *
 * @param conversationId 会话标识
 * @param userId         当前用户，为 null 表示匿名/内部编排调用（比如 DeepResearch 的子任务）
 * @param round          当前轮次序号，从 1 开始
 */
public record HookContext(String conversationId, String userId, int round) {
}
```

### 3.2 `ToolInvocation.java`

```java
package com.agenttrail.loop.hook;

/** 一次具体的工具调用，{@code arguments} 是系统参数注入之后的最终值（模型看不到的 userId 等已经在里面）。 */
public record ToolInvocation(String toolCallId, String toolName, String arguments) {
}
```

### 3.3 六个 Hook 接口

全部是单方法函数式接口，全部 void 返回——这一票不做任何"Hook 决定要不要继续"的控制流改造，
纯粹是通知点。

```java
package com.agenttrail.loop.hook;

/** 每次 {@code AgentLoopExecutor.stream()} 发起新对话时触发一次；{@code resume()} 续接已暂停的
 *  会话不算"新会话开始"，不触发。 */
@FunctionalInterface
public interface SessionStartHook {
    void onSessionStart(HookContext context);
}
```

```java
package com.agenttrail.loop.hook;

/** 一次工具调用**真正执行之前**触发——只对"确实会被执行"的调用触发，命中 HITL 审批而暂停
 *  的调用这一轮不触发（如果后续被批准，会在真正执行的那一刻触发，见 Ticket 2）。 */
@FunctionalInterface
public interface PreToolUseHook {
    void beforeToolUse(HookContext context, ToolInvocation invocation);
}
```

```java
package com.agenttrail.loop.hook;

/** 一次工具调用执行完之后触发（无论成功失败）。{@code success} 的判定复用
 *  {@code AgentLoopExecutor} 已有的 {@code looksLikeToolFailure} 约定
 *  （业务工具以 {@code "Error:"} 开头 / 引擎级失败是 {@code {"error":...}} 形状）。 */
@FunctionalInterface
public interface PostToolUseHook {
    void afterToolUse(HookContext context, ToolInvocation invocation, String result, boolean success);
}
```

```java
package com.agenttrail.loop.hook;

/** 每一轮模型调用结束、拿到 token 用量后触发一次（不区分文本轮/工具调用轮，两种轮次都消耗 token）。 */
@FunctionalInterface
public interface BudgetHook {
    void onRoundUsage(HookContext context, long promptTokens, long completionTokens);
}
```

```java
package com.agenttrail.loop.hook;

/** 一次推理因为异常终止时触发（模型调用失败）；不覆盖"工具调用失败"——那属于正常的
 *  {@link PostToolUseHook#afterToolUse}（{@code success=false}），不是这个循环级别的异常。 */
@FunctionalInterface
public interface OnErrorHook {
    void onError(HookContext context, Throwable error);
}
```

```java
package com.agenttrail.loop.hook;

/** 一次推理结束时触发——正常收尾（{@code success=true}）和异常终止（{@code success=false}）
 *  都会触发；命中 HITL 审批而暂停**不算**结束，不触发（会话还活着，等 resume）。 */
@FunctionalInterface
public interface SessionEndHook {
    void onSessionEnd(HookContext context, boolean success);
}
```

### 3.4 `AgentHooks.java`：装配用的打包对象

对齐 `PauseConfig` 的既有取舍（"暂停需要两个协作参数，拆成两个位置只会让调用点更难读，不如
包一个小对象"）——这里是六个协作列表，更需要打包。

```java
package com.agenttrail.loop.hook;

import java.util.List;

/**
 * 六个拦截点的 Hook 列表打包。同一拦截点可以挂多个实现，按列表顺序依次调用——这一票所有 Hook
 * 都是纯观察型，顺序目前没有语义影响，但保留顺序调用（而不是并行）是为了给以后可能出现的
 * "有依赖关系的 Hook"留余地，不需要现在就决定要不要支持并行。
 */
public record AgentHooks(
        List<SessionStartHook> sessionStart,
        List<PreToolUseHook> preToolUse,
        List<PostToolUseHook> postToolUse,
        List<BudgetHook> budget,
        List<OnErrorHook> onError,
        List<SessionEndHook> sessionEnd) {

    public static final AgentHooks EMPTY =
            new AgentHooks(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());

    public AgentHooks {
        sessionStart = List.copyOf(sessionStart);
        preToolUse = List.copyOf(preToolUse);
        postToolUse = List.copyOf(postToolUse);
        budget = List.copyOf(budget);
        onError = List.copyOf(onError);
        sessionEnd = List.copyOf(sessionEnd);
    }
}
```

## 4. 工具风险分级：`ToolRiskLevel` + `ToolRiskRegistry`

**先验证后确认的结论**（不要照抄 spec 总纲里"Bash/executeSql（写场景）标记为 HIGH_RISK"这句话，
那句话是错的）：逐个读了 `capability/analytics/tools|schema|sql|glossary` 下的实现，
`execute_sql` 走的是 `ReadOnlyQueryRunner`，工具描述原文是"执行**只读**分析 SQL"——
Phase 2 SQL 能力包目前没有任何写库工具。当前代码库里唯一的 `HIGH_RISK` 工具在通用 Runtime 层
（`write_file`/`edit_file`/`bash`），不在 analytics 能力包里。

这一票放在 `com.agenttrail.loop.hook`（不是 `loop.tools`，因为它是治理层的分类元数据，
不是工具实现本身）：

```java
package com.agenttrail.loop.hook;

/** READ_ONLY：不产生外部可见副作用，可以直接放行。HIGH_RISK：会修改文件系统/执行任意命令/
 *  写数据，命中 Ticket 2 的人工审批门禁。没有 WRITE 这个中间档——过早引入一个这次用不上的
 *  第三档是过度设计，等真的出现"介于两者之间"的工具类型再加。 */
public enum ToolRiskLevel {
    READ_ONLY,
    HIGH_RISK
}
```

```java
package com.agenttrail.loop.hook;

import java.util.Map;

/**
 * 工具名到风险等级的分类表。查不到的工具名按 {@link ToolRiskLevel#READ_ONLY} 处理（fail-open）
 * ——这是刻意的取舍：这一票只是把分类结果准备好，还没有任何地方拿它做审批决策（那是 Ticket 2），
 * fail-open 在这一票里不产生任何安全影响；等 Ticket 2 落地、这张表真正驱动审批门禁时，需要
 * 重新评估要不要把"未知工具"的默认值改成 fail-closed（{@code HIGH_RISK}）——新工具上线时忘记
 * 登记分级，不该悄悄被当成安全的放行，这条留给 Ticket 2 做决定，这一票先不下结论。
 */
public final class ToolRiskRegistry {

    private final Map<String, ToolRiskLevel> levels;

    public ToolRiskRegistry(Map<String, ToolRiskLevel> levels) {
        this.levels = Map.copyOf(levels);
    }

    public static ToolRiskRegistry defaults() {
        return new ToolRiskRegistry(Map.ofEntries(
                // 通用 Runtime 工具（loop/tools，Phase 0.9）
                Map.entry("read_file", ToolRiskLevel.READ_ONLY),
                Map.entry("write_file", ToolRiskLevel.HIGH_RISK),
                Map.entry("edit_file", ToolRiskLevel.HIGH_RISK),
                Map.entry("list_files", ToolRiskLevel.READ_ONLY),
                Map.entry("glob_files", ToolRiskLevel.READ_ONLY),
                Map.entry("grep", ToolRiskLevel.READ_ONLY),
                Map.entry("bash", ToolRiskLevel.HIGH_RISK),
                Map.entry("load_file_content", ToolRiskLevel.READ_ONLY),
                // SQL 数据分析能力包（Phase 2）——全部只读，execute_sql 内部走 ReadOnlyQueryRunner
                Map.entry("list_tables", ToolRiskLevel.READ_ONLY),
                Map.entry("describe_tables", ToolRiskLevel.READ_ONLY),
                Map.entry("lookup_glossary", ToolRiskLevel.READ_ONLY),
                Map.entry("validate_sql", ToolRiskLevel.READ_ONLY),
                Map.entry("execute_sql", ToolRiskLevel.READ_ONLY),
                Map.entry("calculate", ToolRiskLevel.READ_ONLY),
                // 元工具/编排工具
                Map.entry("search_tools", ToolRiskLevel.READ_ONLY),
                Map.entry("TodoWrite", ToolRiskLevel.READ_ONLY),
                Map.entry("Skill", ToolRiskLevel.READ_ONLY)
        ));
    }

    public ToolRiskLevel riskOf(String toolName) {
        return levels.getOrDefault(toolName, ToolRiskLevel.READ_ONLY);
    }
}
```

**这次没有覆盖的工具**：Tavily 联网搜索、mcp-echarts 图表生成、PPT/DeepResearch 内部编排用到的
子工具——这些要么是 MCP 动态注册（工具名在运行时才确定，不能硬编码进 `defaults()`），要么这次
没有逐个走查确认。先按"查不到即 READ_ONLY"的 fail-open 兜底覆盖，Ticket 2 开工时如果要让这些
也走审批门禁，需要先补充走查。

## 5. 接入 `AgentLoopExecutor`

### 5.1 新增字段 + 不破坏现有构造函数

**不要**在已有的最长公开构造函数（第 205-227 行，带 `maxConsecutiveToolFailures` 的那个）上加参数
——19 个测试文件直接调用它。做法：新增一个**包私有**的构造函数重载，比公开的那个多一个
`AgentHooks hooks` 参数；公开构造函数内部改为委托给这个新重载，传 `AgentHooks.EMPTY`；
`Builder.build()` 改为调用这个包私有重载，传 `Builder` 里收集到的 `hooks` 字段。

```java
// 字段：紧跟 maxConsecutiveToolFailures 之后
private final AgentHooks hooks;

// 原有公开构造函数（第 205-210 行）内部收尾那一行：
//     this.maxConsecutiveToolFailures = maxConsecutiveToolFailures;
// 之后新增：
//     this.hooks = AgentHooks.EMPTY;
// 或者更彻底：把公开构造函数的方法体改成委托给下面的新重载，避免字段赋值逻辑重复两份：

public AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                         AgentTaskManager taskManager, ContextPolicy contextPolicy,
                         ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                         ToolCatalog toolCatalog, PauseConfig pauseConfig,
                         StageOutputManager stageOutputManager, TraceStore traceStore,
                         MemoryStore memoryStore, FileStore fileStore, int maxConsecutiveToolFailures) {
    this(chatModel, tools, maxRounds, taskManager, contextPolicy, thinkingMode, persistenceHook,
            toolCatalog, pauseConfig, stageOutputManager, traceStore, memoryStore, fileStore,
            maxConsecutiveToolFailures, AgentHooks.EMPTY);
}

// 新增的包私有重载，真正的字段赋值逻辑搬到这里：
AgentLoopExecutor(ChatModel chatModel, List<ToolCallback> tools, int maxRounds,
                  AgentTaskManager taskManager, ContextPolicy contextPolicy,
                  ThinkingMode thinkingMode, TurnPersistenceHook persistenceHook,
                  ToolCatalog toolCatalog, PauseConfig pauseConfig,
                  StageOutputManager stageOutputManager, TraceStore traceStore,
                  MemoryStore memoryStore, FileStore fileStore, int maxConsecutiveToolFailures,
                  AgentHooks hooks) {
    this.llmInvoker = new LlmInvoker(chatModel);
    // ... 其余字段赋值逻辑原样从原构造函数搬过来 ...
    this.hooks = (hooks == null) ? AgentHooks.EMPTY : hooks;
}
```

`Builder` 加一个字段和方法（对齐 `maxConsecutiveToolFailures` 那个方法的写法）：

```java
private AgentHooks hooks = AgentHooks.EMPTY;

public Builder hooks(AgentHooks hooks) {
    this.hooks = hooks;
    return this;
}
```

`Builder.build()`（第 320-324 行）改为调用新的包私有重载，多传一个 `hooks` 参数。

### 5.2 六个拦截点的精确调用位置

| Hook | 方法 | 精确位置 | 说明 |
|---|---|---|---|
| `SessionStartHook` | `stream()` | 第 384-386 行之间：`RunContext context = new RunContext(...)` 之后、`context.emit(AgentStart)` 之前 | 只在 `stream()`，不在 `resume()`——续接暂停的会话不算新会话开始 |
| `BudgetHook` | `finishRound()` | 两处 `recordTrace(...)` 调用之后各加一次：TEXT 分支（原第 520 行后）、工具调用分支（原第 529 行后） | 每轮都触发，不区分是不是最后一轮 |
| `PreToolUseHook` | `finishRound()` + `executePendingToolCalls()` | 分别在两处 `toolCallExecutor.execute(...)` 调用**之前**，对 `toolCalls`/`approvedCalls` 列表逐个触发 | 只对"确实要执行"的调用触发，暂停中的不触发（见 3.3 节接口注释） |
| `PostToolUseHook` | `finishRound()` + `executePendingToolCalls()` | 分别在两处 `toolCallExecutor.execute(...)` 调用**之后**，对返回的 `responses` 列表逐个触发，`success` 用已有的私有静态方法 `looksLikeToolFailure(response.responseData())` 取反 | 两处都要接，`executePendingToolCalls()` 目前没有 `context`（还没建出新 `RunContext`，见原代码第 703-705 行注释），需要用 `paused.conversationId()`/`paused.params().userId()`/`paused.roundAtPause()` 现凑一个 `HookContext`，不能假设这里已经有 `context` 变量 |
| `OnErrorHook` | `failRun()` | `recordTrace(...)` 调用之后、`emit(Error)` 之前 | |
| `SessionEndHook` | `completeRun()` + `failRun()` | `completeRun()` 里 `emit(Complete)` 之前触发 `success=true`；`failRun()` 里 `emit(Error)` 之前触发 `success=false` | `pauseForApproval()` 不触发——会话没结束，只是暂停 |

为了避免每个调用点都手写"遍历列表挨个调用"，加一批私有 helper 方法（放在 `AgentLoopExecutor`
里，和 `checkConsecutiveToolFailures` 这类既有私有方法风格一致）：

```java
private void fireSessionStart(RunContext context) {
    HookContext hookContext = new HookContext(context.conversationId(), context.params().userId(), 0);
    hooks.sessionStart().forEach(hook -> hook.onSessionStart(hookContext));
}

private void fireBudget(RunContext context, RoundState state) {
    HookContext hookContext = toHookContext(context);
    hooks.budget().forEach(hook -> hook.onRoundUsage(hookContext, state.promptTokens(), state.completionTokens()));
}

private void firePreToolUse(HookContext hookContext, List<AssistantMessage.ToolCall> toolCalls) {
    toolCalls.forEach(call -> {
        ToolInvocation invocation = new ToolInvocation(call.id(), call.name(), call.arguments());
        hooks.preToolUse().forEach(hook -> hook.beforeToolUse(hookContext, invocation));
    });
}

private void firePostToolUse(HookContext hookContext, List<ToolResponseMessage.ToolResponse> responses) {
    responses.forEach(response -> {
        ToolInvocation invocation = new ToolInvocation(response.id(), response.name(), null);
        boolean success = !looksLikeToolFailure(response.responseData());
        hooks.postToolUse().forEach(hook -> hook.afterToolUse(hookContext, invocation, response.responseData(), success));
    });
}

private void fireOnError(RunContext context, Throwable error) {
    hooks.onError().forEach(hook -> hook.onError(toHookContext(context), error));
}

private void fireSessionEnd(RunContext context, boolean success) {
    hooks.sessionEnd().forEach(hook -> hook.onSessionEnd(toHookContext(context), success));
}

private static HookContext toHookContext(RunContext context) {
    return new HookContext(context.conversationId(), context.params().userId(), context.roundCounter().get());
}
```

`executePendingToolCalls()` 里没有 `RunContext`，`firePreToolUse`/`firePostToolUse` 直接传一个
现场构造的 `HookContext`（用 `paused.*` 取值），不能复用 `toHookContext(RunContext)` 这个重载
——**先验证**：确认 `PauseState` 有 `conversationId()`/`params()`/`roundAtPause()` 这几个访问器
（第 614 行 `new PauseState(context.conversationId(), ...)` 的构造参数顺序能看出字段，但要读
`PauseState.java` 确认访问器方法名，不要假设和构造参数顺序完全一致）。

### 5.3 `AgentLoopExecutorFactory` 接入

`buildExecutor`（第 150-161 行）和 `forAnalytics`（第 191-201 行）两处 `Builder` 链**都要**加
`.hooks(sharedHooks)`——这是最容易漏掉一处的地方，两个方法是各自独立的 `Builder` 链，不共享代码。
这一票 `sharedHooks` 就是 `AgentHooks.EMPTY`（或者装配一个只有测试/日志用途的占位实现），真正有
内容的 Hook 实现由 Ticket 2/4/5/9 往同一个共享 Bean 里追加。

## 6. Testing Decisions

- 六个拦截点各自的触发次数测试：一次只有文本回复的对话（触发 `SessionStart`×1、`Budget`×1、
  `SessionEnd`×1，`PreToolUse`/`PostToolUse`/`OnError` 均为 0 次）；一次带两个并发工具调用的对话
  （`PreToolUse`/`PostToolUse` 各触发 2 次，顺序和工具调用顺序一致）；一次命中 HITL 审批而暂停的
  对话（`SessionEnd` 不触发，因为会话没结束）
- 零行为变化回归：现有 `AgentLoopExecutorTest` 等测试套件不改一行断言，全部继续通过
- `ToolRiskRegistry.defaults()` 对第 4 节表格里每一个工具名做一次断言，外加一个不存在的工具名
  断言返回 `READ_ONLY`
- Hook 实现内部抛异常时的行为**这一票不做特殊处理**（异常会顺着 `forEach` 冒出来，可能中断当前
  轮次）——这是已知的粗糙点，留给 Ticket 2/4/5/9 各自实现时决定要不要在 `fireXxx` 里包一层
  try-catch 兜底；这一票的默认 Hook 列表永远是空的，不会真的触发这个问题，没必要现在就设计
  一套异常隔离策略

## Out of Scope（与总纲一致，这里重复一遍避免翻文档）

- 用风险分级驱动审批（Ticket 2）
- 任何具体 Hook 的业务实现（Ticket 4/5/9）
- MCP 动态工具的风险分级
