# Ticket 12（Phase 1）：冻结 AgentRuntimePort 契约 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。Blocked by [Ticket 11](refactor-remediation-ticket-11.md)。Blocks [Ticket 13](refactor-remediation-ticket-13.md)。

## 0. 范围边界

这一票**只新增接口和值类型，不删除、不修改**任何现有 `AgentLoopExecutor`/`AgentLoopExecutorFactory`
代码——新端口先由一个 `LegacyAgentLoopExecutorAdapter` 委托给现有执行器实现，两条路径并存。
验收标准（照抄 `refactor-blueprint.md` §6 Phase 1）：**"新业务只允许依赖 `AgentRuntimePort`；
旧业务仍可运行"**——这一票不强制迁移任何现有调用点（`DeepResearchService`/PPT 策略类是 Ticket
13/16-18 的事），只是把新端口建好、能用。

**先验证**（开工前务必重读一遍，接口签名和字段可能已经和这里写的有出入）：`AgentLoopExecutor`
（`src/main/java/com/agenttrail/loop/core/AgentLoopExecutor.java`）当前 1206 行，
`stream(String, RunnableParams)`/`call(String, RunnableParams)`/`resume(String, ResumeInstruction)`
三个公开方法的签名以第 539/616/928 行的真实声明为准；`RunnableParams`
（`src/main/java/com/agenttrail/loop/model/RunnableParams.java`）是一个四字段 record
（`conversationId`/`userId`/`toolParams`/`outputType`）；`AgentStreamEvent`
（`src/main/java/com/agenttrail/loop/model/AgentStreamEvent.java`）是一个 sealed interface，
当前有 9 个变体（`AgentStart`/`Text`/`Thinking`/`ToolStart`/`ToolEnd`/`StageOutput`/
`TodoProgress`/`Paused`/`Error`/`Complete`）。

## 1. `platform.ids`：强类型标识

新增 `com.agenttrail.platform.ids` 包，四个 record，包一层 `String`/`UUID` 而不是让业务代码
到处传裸字符串——对齐 `refactor-blueprint.md` §5 结尾的强类型约束（"`AgentRequest` 的身份、
租户、预算、工具权限必须是强类型，禁止塞进 `Map`"）。

```java
package com.agenttrail.platform.ids;

import java.util.Objects;
import java.util.UUID;

public record RunId(String value) {
    public RunId {
        Objects.requireNonNull(value, "value");
    }
    public static RunId newId() {
        return new RunId(UUID.randomUUID().toString());
    }
    public static RunId of(String value) {
        return new RunId(value);
    }
}
```

`TaskId`/`ConversationId`/`ArtifactId`结构完全一致（同一份模板换类名），不重复贴代码。

**已知的语义落差，写票时先记下**：当前代码库里已经有两处叫"taskId"的东西——`DeepResearchController`/
`PptGenerationController` 的轮询任务号（前者是内存注册表的自增 `long`，见
`DeepResearchTaskRegistry`；后者是 PPT 任务表主键 `long`）——它们是**业务层的任务号**，和这里
新增的 `platform.ids.TaskId`（Runtime/Workflow 层，Phase 4/Ticket 15 才会真正用起来）不是同一个
概念，这一票不做也不需要做两者之间的映射，只是先把类型建好。`ConversationId` 则直接对应现有
`RunnableParams.conversationId()`/`AgentLoopExecutor` 各处的 `conversationId` 字符串，是这一票
唯一马上会被用到的 id 类型。

## 2. `platform.identity` / `platform.error`：最小可用骨架

**这两个包先建最小可用的骨架，不需要预判所有未来错误码**，够 Phase 1-2 用即可：

```java
package com.agenttrail.platform.identity;

/** @param userId 对应 {@code RunnableParams.userId()}/{@code StpUtil.getLoginIdAsString()} 的取值 */
public record Principal(String userId) {
    public static final Principal ANONYMOUS = new Principal(null);
}

/**
 * 当前工程没有多租户（`refactor-remediation.md` 明确把多租户改造列为 Out of Scope，需要单独立项），
 * 这里只放一个恒定的默认值占位，不接任何真实租户解析逻辑——先把类型摆在该在的位置，
 * 避免真正做多租户时再回头把 TenantId 到处塞进方法签名。
 */
public record TenantContext(String tenantId) {
    public static final TenantContext DEFAULT = new TenantContext("default");
}
```

```java
package com.agenttrail.platform.error;

public enum RetryClass {
    NONE, RETRIABLE, RATE_LIMITED, FATAL
}

/**
 * 已知取值直接照抄 {@code AgentLoopExecutor}/{@code AgentCallException} 里已经在用的错误码字符串
 * （第 543/551/627-628/1101 行），保证一比一映射，不凭空发明新码。
 */
public record ErrorCode(String code, RetryClass retryClass) {
    public static final ErrorCode CONCURRENT_EXECUTION = new ErrorCode("CONCURRENT_EXECUTION", RetryClass.NONE);
    public static final ErrorCode PROMPT_INJECTION_DETECTED = new ErrorCode("PROMPT_INJECTION_DETECTED", RetryClass.FATAL);
    public static final ErrorCode LLM_CALL_FAILED = new ErrorCode("LLM_CALL_FAILED", RetryClass.RETRIABLE);
    public static final ErrorCode PAUSED = new ErrorCode("PAUSED", RetryClass.NONE);

    /** 兜底：将来 {@code AgentLoopExecutor} 新增错误码字符串，转换层不应该因为"没见过"就崩溃。 */
    public static ErrorCode unknown(String code) {
        return new ErrorCode(code, RetryClass.RETRIABLE);
    }
}
```

## 3. `runtime.api`：`AgentRuntimePort` 契约本体

新增 `com.agenttrail.runtime.api` 包。接口签名照抄 `refactor-blueprint.md` §1.8 给出的定义：

```java
package com.agenttrail.runtime.api;

public interface AgentRuntimePort {
    AgentRunHandle start(AgentRequest request);
    AgentResult call(AgentRequest request);
    AgentRunSnapshot snapshot(RunId runId);
    void cancel(RunId runId, CancellationReason reason);
    AgentRunHandle resume(RunId runId, ResumeCommand command);
}
```

配套值类型：

```java
package com.agenttrail.runtime.api;

import com.agenttrail.loop.model.OutputType; // 见下方"已知缺口/临时耦合"说明
import com.agenttrail.platform.identity.Principal;
import com.agenttrail.platform.ids.ConversationId;

import java.time.Duration;
import java.util.Map;

public record AgentRequest(
        ConversationId conversationId,
        Principal principal,
        String message,
        Map<String, Object> toolParams,
        OutputType outputType,
        Budget budget) {

    /**
     * 旧代码没有对应物——{@code SessionBudgetTracker}（AgentLoopExecutorConfig 第 138-141 行）
     * 是装配时固定的会话级 token 上限，不是调用方按次传入的预算。这一票里 {@link Budget} 只是
     * 占位类型，{@code LegacyAgentLoopExecutorAdapter} 会原样忽略它，不接入任何真实限流——
     * 已知缺口，留给 Phase 2 之后视需要再决定要不要打通到 {@code SessionBudgetTracker}
     * 或新的 Runtime 级预算机制。
     */
    public record Budget(Long maxTokens, Duration maxWallClock) {
        public static final Budget UNBOUNDED = new Budget(null, null);
    }
}
```

```java
package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.RunId;
import org.reactivestreams.Publisher;

/**
 * {@code events()} 类型是 {@link Publisher} 而不是 {@code reactor.core.publisher.Flux}——
 * {@code Publisher} 是 Reactive Streams 规范本身的接口，不是 Reactor 库私有类型，这样
 * {@code runtime.api} 包不直接依赖 {@code reactor-core}（对齐 §5 的"Reactor 类型只允许出现在
 * adapter 层"），调用方需要 Flux 语义时自己 {@code Flux.from(handle.events())} 包一层——
 * Spring Boot 已经传递依赖了 {@code reactive-streams}，不需要新增坐标。
 */
public record AgentRunHandle(RunId runId, Publisher<AgentEvent> events) {
}
```

```java
package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.RunId;

public record AgentResult(RunId runId, String text) {
}
```

```java
package com.agenttrail.runtime.api;

import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;

/**
 * 目标态字段——{@code LegacyAgentLoopExecutorAdapter} 目前**造不出**这个类型的真实数据
 * （见 §5 "snapshot 不支持"），这里先把契约形状定下来，供 Phase 4（Ticket 15）统一
 * Run/Task/Checkpoint 基础设施后填上真实实现。
 */
public record AgentRunSnapshot(RunId runId, ConversationId conversationId, RunStatus status, int roundCount) {
    public enum RunStatus { RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED, UNKNOWN }
}
```

```java
package com.agenttrail.runtime.api;

public record CancellationReason(String description) {
    public static final CancellationReason USER_REQUESTED = new CancellationReason("user_requested");
}
```

```java
package com.agenttrail.runtime.api;

/** 对应旧 {@code loop.pause.ResumeInstruction} 的两个分支（HITL 审批 / 带新指令中断）。 */
public sealed interface ResumeCommand {
    record Approve() implements ResumeCommand {
    }
    record Reject(String reason) implements ResumeCommand {
    }
    record WithNewInstruction(String message) implements ResumeCommand {
    }
}
```

```java
package com.agenttrail.runtime.api;

import com.agenttrail.platform.error.ErrorCode;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;

/**
 * 事件形状对齐旧 {@code AgentStreamEvent} 的 7 个可迁移变体——{@code StageOutput}/{@code TodoProgress}
 * 这两个变体这一票不建，见 §5 的"已核实的生产零使用"说明。这不是 Phase 4（Ticket 15）
 * 要建的完整 {@code EventEnvelope}（eventId/sequence/occurredAt/visibility 那一套），
 * 那是往后 Phase 才做的事——这里只求"契约冻结、能包住现有事件"，先不做事件溯源/重放。
 */
public sealed interface AgentEvent {
    record Started(RunId runId, ConversationId conversationId) implements AgentEvent {
    }
    record TextDelta(RunId runId, String content) implements AgentEvent {
    }
    record ThinkingDelta(RunId runId, String content) implements AgentEvent {
    }
    record ToolStarted(RunId runId, String toolName, String toolCallId, String arguments) implements AgentEvent {
    }
    record ToolCompleted(RunId runId, String toolName, String toolCallId, String result) implements AgentEvent {
    }
    record Paused(RunId runId, ConversationId conversationId, String reason) implements AgentEvent {
    }
    record Failed(RunId runId, ErrorCode errorCode, String message) implements AgentEvent {
    }
    record Completed(RunId runId, ConversationId conversationId, Long turnId) implements AgentEvent {
    }
}
```

**已知缺口/临时耦合，写票时明确标注，不要假装它不存在**：`AgentRequest` 直接复用了旧
`com.agenttrail.loop.model.OutputType`，这让新的 `runtime.api` 包临时依赖了一个旧 `loop.model`
类型——严格按 §5 的目标依赖方向这是不允许的，但这一票的定位是"冻结契约、不引入不必要的重复
类型"，`OutputType` 本身只是一个纯值枚举/描述，不携带 Spring AI 依赖，重复定义一份意义不大；
这条耦合记入 Ticket 11 建的 ArchUnit TODO 清单，交给 Phase 3（Ticket 14）或更后面的 Phase 决定
要不要把 `OutputType` 挪进 `runtime.api` 或 `platform`。

## 4. `LegacyAgentLoopExecutorAdapter`：这张票最核心的部分

新增 `com.agenttrail.runtime.api.legacy.LegacyAgentLoopExecutorAdapter implements AgentRuntimePort`，
内部持有一个 `AgentLoopExecutor` 实例，`start`/`call` 把新 `AgentRequest` 转换成旧的
`question, RunnableParams` 调用现有 `stream()`/`call()`，把旧的 `AgentStreamEvent`/
`AgentCallException` 转换回新的 `AgentEvent`/异常体系。

**构造时的关键坑**：`AgentLoopExecutor.taskManager` 是 `private final` 字段，没有 getter
（第 103/349 行），`cancel()` 要能真正停掉正在跑的任务，adapter 必须**另外**接收一份
`AgentTaskManager`，且这份实例必须和构造 `delegate` 时用的是**同一个**——生产装配下两者都应该
来自 `AgentLoopExecutorConfig.agentTaskManager()` 这个单例 Bean；如果谁传了一个新 `new` 出来的
`AgentTaskManager`，`cancel()` 会因为查错 map 永远返回"没有正在跑的任务"，这是一个容易踩、
但编译期完全发现不了的错误，实现和测试时都要显式验证这一点。

```java
package com.agenttrail.runtime.api.legacy;

import com.agenttrail.loop.core.AgentCallException;
import com.agenttrail.loop.core.AgentLoopExecutor;
import com.agenttrail.loop.model.AgentStreamEvent;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.pause.ResumeInstruction;
import com.agenttrail.loop.task.AgentTaskManager;
import com.agenttrail.platform.error.ErrorCode;
import com.agenttrail.platform.ids.ConversationId;
import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

public class LegacyAgentLoopExecutorAdapter implements AgentRuntimePort {

    private static final Logger log = LoggerFactory.getLogger(LegacyAgentLoopExecutorAdapter.class);

    private final AgentLoopExecutor delegate;
    /** 必须和 {@link #delegate} 内部实际使用的是同一个实例——见本节开头的说明。 */
    private final AgentTaskManager taskManager;

    public LegacyAgentLoopExecutorAdapter(AgentLoopExecutor delegate, AgentTaskManager taskManager) {
        this.delegate = delegate;
        this.taskManager = taskManager;
    }

    @Override
    public AgentRunHandle start(AgentRequest request) {
        // 这个 adapter 里 RunId 直接借用 conversationId 的取值——AgentLoopExecutor 的并发/单飞
        // 语义天然是"按会话"而不是"按次调用"，一次 stream() 调用没有独立于 conversationId 的
        // 运行时标识可用；这是 adapter 特有的简化，不是 AgentRuntimePort 契约本身的约束
        // （未来真正的 Runtime 实现里一个会话可以有多个 run，见 Phase 4）。
        RunId runId = RunId.of(request.conversationId().value());
        RunnableParams params = toRunnableParams(request);
        Flux<AgentEvent> events = delegate.stream(request.message(), params)
                .map(event -> toAgentEvent(runId, request.conversationId(), event));
        return new AgentRunHandle(runId, events);
    }

    @Override
    public AgentResult call(AgentRequest request) {
        RunId runId = RunId.of(request.conversationId().value());
        try {
            String text = delegate.call(request.message(), toRunnableParams(request));
            return new AgentResult(runId, text);
        } catch (AgentCallException legacyFailure) {
            throw new AgentRuntimeException(mapErrorCode(legacyFailure.code()), legacyFailure.getMessage());
        }
    }

    /**
     * 已知缺口：{@code AgentLoopExecutor}/{@code AgentTaskManager} 都不对外暴露"这次 run 跑到
     * 第几轮、当前消息历史是什么"这类查询接口——{@code TaskInfo} 是 {@code AgentTaskManager} 的
     * 包内私有内部结构，只服务于单飞/停止两个用途。真正能回答"这次 run 现在是什么状态"要等
     * Ticket 15 统一 Run/Task/Checkpoint 基础设施、把状态显式落到可查询的存储之后才有数据源。
     * 这里直接抛异常而不是拼一个半真半假的 {@link AgentRunSnapshot}，避免调用方误以为拿到的是
     * 可信数据。
     */
    @Override
    public AgentRunSnapshot snapshot(RunId runId) {
        throw new UnsupportedOperationException(
                "LegacyAgentLoopExecutorAdapter 不支持 snapshot（已知的 Phase 1 缺口，"
                        + "见 Ticket 12 §4）：runId=" + runId);
    }

    @Override
    public void cancel(RunId runId, CancellationReason reason) {
        boolean stopped = taskManager.stopTask(runId.value());
        if (!stopped) {
            log.debug("cancel({}) 命中时该会话已经不在跑，忽略（reason={}）", runId, reason.description());
        }
    }

    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        ResumeInstruction instruction = toResumeInstruction(command);
        ConversationId conversationId = new ConversationId(runId.value());
        Flux<AgentEvent> events = delegate.resume(runId.value(), instruction)
                .map(event -> toAgentEvent(runId, conversationId, event));
        return new AgentRunHandle(runId, events);
    }

    private RunnableParams toRunnableParams(AgentRequest request) {
        // request.budget() 故意不使用——AgentRequest.Budget 的已知缺口，见 §3 的类型定义注释
        String userId = request.principal() == null ? null : request.principal().userId();
        return new RunnableParams(request.conversationId().value(), userId,
                request.toolParams(), request.outputType());
    }

    private static AgentEvent toAgentEvent(RunId runId, ConversationId conversationId, AgentStreamEvent legacy) {
        return switch (legacy) {
            case AgentStreamEvent.AgentStart ignored -> new AgentEvent.Started(runId, conversationId);
            case AgentStreamEvent.Text text -> new AgentEvent.TextDelta(runId, text.content());
            case AgentStreamEvent.Thinking thinking -> new AgentEvent.ThinkingDelta(runId, thinking.content());
            case AgentStreamEvent.ToolStart start ->
                    new AgentEvent.ToolStarted(runId, start.toolName(), start.toolCallId(), start.arguments());
            case AgentStreamEvent.ToolEnd end ->
                    new AgentEvent.ToolCompleted(runId, end.toolName(), end.toolCallId(), end.result());
            case AgentStreamEvent.Paused paused -> new AgentEvent.Paused(runId, conversationId, paused.reason().name());
            case AgentStreamEvent.Error error -> new AgentEvent.Failed(runId, mapErrorCode(error.code()), error.message());
            case AgentStreamEvent.Complete complete -> new AgentEvent.Completed(runId, conversationId, complete.turnId());
            // 已核实（2026-08-11）：StageOutputManager 生产装配下永远是 EMPTY（AgentLoopExecutorFactory
            // 的 buildExecutor/forAnalytics 都没调用 .stageOutputManager(...)），TodoWriteTool 没有
            // 任何生产实例化点（全仓库 `new TodoWriteTool(` 零命中）——这两个事件目前不会真的发生。
            // 一旦真的发生，宁可在这里立刻炸掉，也不要在事件流里悄悄丢事件；哪个先被接入生产，
            // 就在那张票里给它加对应的 AgentEvent 变体，不用这一票预判。
            case AgentStreamEvent.StageOutput ignored -> throw new UnsupportedOperationException(
                    "AgentStreamEvent.StageOutput 暂无对应 AgentEvent 变体（Hook/StageOutputProvider "
                            + "去留由 Ticket 14 决定）；生产环境理论上不会走到这里，见 Ticket 12 §4 核实记录");
            case AgentStreamEvent.TodoProgress ignored -> throw new UnsupportedOperationException(
                    "AgentStreamEvent.TodoProgress 暂无对应 AgentEvent 变体（TodoWriteTool 未接入生产）；"
                            + "生产环境理论上不会走到这里，见 Ticket 12 §4 核实记录");
        };
    }

    private static ResumeInstruction toResumeInstruction(ResumeCommand command) {
        return switch (command) {
            case ResumeCommand.Approve ignored -> ResumeInstruction.ApprovalDecision.approve();
            case ResumeCommand.Reject reject -> ResumeInstruction.ApprovalDecision.reject(reject.reason());
            case ResumeCommand.WithNewInstruction newInstruction ->
                    new ResumeInstruction.NewInstruction(newInstruction.message());
        };
    }

    private static ErrorCode mapErrorCode(String legacyCode) {
        return switch (legacyCode) {
            case "CONCURRENT_EXECUTION" -> ErrorCode.CONCURRENT_EXECUTION;
            case "PROMPT_INJECTION_DETECTED" -> ErrorCode.PROMPT_INJECTION_DETECTED;
            case "LLM_CALL_FAILED" -> ErrorCode.LLM_CALL_FAILED;
            case "PAUSED" -> ErrorCode.PAUSED; // 只有 call() 的 AgentCallException 会带这个码
            default -> ErrorCode.unknown(legacyCode);
        };
    }
}
```

配套的 `AgentRuntimeException`：

```java
package com.agenttrail.runtime.api;

import com.agenttrail.platform.error.ErrorCode;

public class AgentRuntimeException extends RuntimeException {
    private final ErrorCode errorCode;

    public AgentRuntimeException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
```

**先验证**：`ResumeInstruction.ApprovalDecision.approve()`/`.reject(String)` 和
`ResumeInstruction.NewInstruction` 的真实构造方式以 `src/main/java/com/agenttrail/loop/pause/ResumeInstruction.java`
当前声明为准，上面代码框架按 `AgentLoopExecutor.java` 第 973-980 行的调用方式反推得出，
实现时要打开这个文件核对，不要直接照抄。

## 5. `RunnableParams` 标记 `@Deprecated`

```java
/**
 * @deprecated 新代码走 {@link com.agenttrail.runtime.api.AgentRequest} +
 *             {@link com.agenttrail.runtime.api.AgentRuntimePort}。这个类型继续在
 *             {@code loop.core.AgentLoopExecutor} 内部实现和
 *             {@link com.agenttrail.runtime.api.legacy.LegacyAgentLoopExecutorAdapter} 的
 *             转换层里使用（Phase 3/Ticket 14 拆分 Runtime 内部模块之前不会消失），
 *             不应该出现在新业务代码的公开签名里。
 */
@Deprecated
public record RunnableParams(String conversationId, String userId, Map<String, Object> toolParams,
                             OutputType outputType) {
    // 字段和构造函数本身不变
}
```

标记之后 `AgentLoopExecutor`/`AgentLoopExecutorFactory`/`AgentLoopController`/PPT 策略类/
`DeepResearchService` 等既有调用点会出现编译期 deprecation 警告——**这是预期效果，不是要在这一票
里挨个消掉**，警告本身就是"新代码不该再这样写"的提醒，消警告是 Ticket 13/16-18 迁移调用点时
顺带做的事。

## 6. Fake 实现

新增 `com.agenttrail.runtime.api.support.FakeAgentRuntimePort`（测试代码，放在
`src/test/java/com/agenttrail/runtime/api/support/`，命名和目录约定对齐现有的
`com.agenttrail.loop.core.support.ScriptedChatModel`——项目里手写测试替身统一放在同包下的
`support` 子包，`Fake`/`Scripted`/`Recording` 前缀按"是否需要回放脚本"/"是否需要录制调用参数"
的语义选，不是互相冲突的两套命名规范）：

```java
package com.agenttrail.runtime.api.support;

import com.agenttrail.platform.ids.RunId;
import com.agenttrail.runtime.api.*;

import java.util.*;

/**
 * {@link AgentRuntimePort} 的测试替身：按 {@code conversationId} 预置一份固定
 * {@link AgentEvent} 序列，{@code start}/{@code resume} 直接回放，{@code call} 返回预置文本——
 * 不经过任何真实 {@code AgentLoopExecutor}/{@code ChatModel}，供业务层单测使用。
 */
public class FakeAgentRuntimePort implements AgentRuntimePort {

    private final Map<String, List<AgentEvent>> scriptedEvents = new HashMap<>();
    private final Map<String, String> scriptedTexts = new HashMap<>();
    private final List<AgentRequest> receivedRequests = new ArrayList<>();

    public FakeAgentRuntimePort scriptEvents(String conversationId, AgentEvent... events) {
        scriptedEvents.put(conversationId, List.of(events));
        return this;
    }

    public FakeAgentRuntimePort scriptCallResult(String conversationId, String text) {
        scriptedTexts.put(conversationId, text);
        return this;
    }

    public List<AgentRequest> receivedRequests() {
        return List.copyOf(receivedRequests);
    }

    @Override
    public AgentRunHandle start(AgentRequest request) {
        receivedRequests.add(request);
        RunId runId = RunId.of(request.conversationId().value());
        List<AgentEvent> scripted = scriptedEvents.getOrDefault(request.conversationId().value(), List.of());
        return new AgentRunHandle(runId, reactor.core.publisher.Flux.fromIterable(scripted));
    }

    @Override
    public AgentResult call(AgentRequest request) {
        receivedRequests.add(request);
        RunId runId = RunId.of(request.conversationId().value());
        String text = scriptedTexts.getOrDefault(request.conversationId().value(), "");
        return new AgentResult(runId, text);
    }

    @Override
    public AgentRunSnapshot snapshot(RunId runId) {
        return new AgentRunSnapshot(runId, new com.agenttrail.platform.ids.ConversationId(runId.value()),
                AgentRunSnapshot.RunStatus.UNKNOWN, 0);
    }

    @Override
    public void cancel(RunId runId, CancellationReason reason) {
        // 测试替身不需要真的停止任何东西，留空即可
    }

    @Override
    public AgentRunHandle resume(RunId runId, ResumeCommand command) {
        return start(new AgentRequest(new com.agenttrail.platform.ids.ConversationId(runId.value()),
                null, "", Map.of(), null, AgentRequest.Budget.UNBOUNDED));
    }
}
```

`FakeAgentRuntimePort` 里唯一用到 `reactor.core.publisher.Flux` 的地方是内部构造 `AgentRunHandle`
时给一个具体的 `Publisher` 实现——这不违反"`runtime.api` 不依赖 Reactor"的原则，因为
`FakeAgentRuntimePort` 本身是测试代码，不是 `runtime.api` 包内的生产类型。

## Testing Decisions

- `LegacyAgentLoopExecutorAdapterTest`（集成测试，`src/test/java/com/agenttrail/runtime/api/legacy/`）：
  用同一个 `ScriptedChatModel` 脚本分别构造一个裸 `AgentLoopExecutor` 和一个包了它的
  `LegacyAgentLoopExecutorAdapter`，两条路径各自跑一遍相同的 `question`/`RunnableParams` vs
  `AgentRequest`，断言两者产出的最终文本一致、事件数量和顺序一一对应（除去按 §4 已核实不会
  发生的 `StageOutput`/`TodoProgress`）
- `cancel()` 的"必须共用同一个 `AgentTaskManager`"这条坑要有一条专门的测试：构造 adapter 时故意
  传一个和 `delegate` 内部不一致的 `AgentTaskManager`，断言 `cancel()` 静默失效（`stopTask`
  返回 false，`log.debug` 记录，但不抛异常）——这条测试本身就是把"容易踩的坑"变成"回归网",
  防止以后有人在装配代码里不小心传错实例
- `call()` 的异常映射：分别触发 `CONCURRENT_EXECUTION`/`LLM_CALL_FAILED`/未知错误码三种场景，
  断言 `AgentRuntimeException.errorCode()` 分别映射到对应的 `ErrorCode` 常量或
  `ErrorCode.unknown(...)`
- `FakeAgentRuntimePort` 本身只需要"回放脚本、记录收到的请求"这两个基本行为的冒烟测试，
  不需要覆盖到和真实 adapter 一样的深度——它是给业务层单测用的替身，不是被测对象本身

## Out of Scope

- 不改 `DeepResearchService`/PPT 策略的调用方式——它们继续直接持有 `AgentLoopExecutor` 字段、
  继续调用 `.call(...)`，这是 Ticket 13/16-18 的事
- 不强制迁移任何现有调用点到 `AgentRuntimePort`——这一票只要求新端口"建好、能用、有验证"
- `AgentRunSnapshot`/`Budget` 的真实实现——两者都是已记录的已知缺口，留给 Phase 4 及之后
- 完整的 `EventEnvelope`（eventId/sequence/occurredAt/visibility 那一套，支持断线重放）——
  那是 Phase 4（Ticket 15）的范围，这一票的 `AgentEvent` 只求"契约冻结、包住现有事件"
