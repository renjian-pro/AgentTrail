# Ticket 2／9：高危操作转人工审批 + 会话级 Budget 熔断 — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)，依赖
> [Ticket 1](backend-phase3-governance-ticket-01.md) 的 `ToolRiskRegistry`/`AgentHooks`。

## 0. 范围边界

**这一票只做**：
1. 把 `PauseConfig`（issue #13 已经实现、但从未接入生产装配）**第一次**接入
   `AgentLoopExecutorFactory`，`approvalRequiredTools` 由 Ticket 1 的 `ToolRiskRegistry` 算出来，
   不再是空集合
2. 会话级 Budget 熔断：**纯内存**、按 `conversationId` 累加 token，超过阈值时下一轮开始前直接
   终止——不依赖任何数据库

**这一票不做**：
- 跨会话/按自然日的成本聚合和熔断——那需要读 Ticket 4 落地的 `TraceStore` 生产数据做聚合查询，
  这一票只做单会话维度，见 Out of Scope
- "超预算降级到更便宜模型"——总纲已经写明这次只做熔断
- `WRITE` 风险档的审批流程——Ticket 1 定的二档模型，这一票只处理 `HIGH_RISK`

## 1. 一个必须先确认的现状：PauseConfig 从未在生产环境启用过

**这不是这一票要新增的机制，是要补的一个装配缺口**，性质和 issue 之前"Redis 任务锁写好了但
`AgentLoopExecutorConfig` 一直只 `new AgentTaskManager()`"是同一类问题：

- `PauseConfig`（`loop/pause/PauseConfig.java`）、`JdbcPauseStateStore`、暂停/恢复的核心循环逻辑
  （`AgentLoopExecutor.pauseForApproval`/`resume`）issue #13 都已经实现并有测试覆盖
- 但 `AgentLoopExecutorFactory` 的字段列表（`web/AgentLoopExecutorFactory.java` 第 43-70 行）里
  **没有 `PauseConfig` 这个字段**，`buildExecutor`/`forAnalytics` 两处 `Builder` 链
  （原第 150-161、191-201 行）都没有调用 `.pauseConfig(...)`——现在生产环境里高危工具会直接执行，
  不会触发任何审批
- `AgentLoopExecutorConfig.java` 第 42-43 行的注释也如实写着"暂停恢复……仍按场景作为可选机制扩展"

这一票要把这个缺口连着"风险分级驱动审批名单"一起补上，不是分两次做。

## 2. `PauseConfig` 生产装配

### 2.1 `PauseConfig` 需不需要改？—— 不需要

`PauseConfig.approvalRequiredTools()` 已经是任意 `Set<String>`，不需要改 `PauseConfig` 本身的
形状，只需要在**构造它的地方**把这个集合换成"`ToolRiskRegistry` 里所有 `HIGH_RISK` 的工具名"。
`ToolRiskRegistry`（Ticket 1）加一个方法：

```java
// ToolRiskRegistry 新增方法
public Set<String> toolsWithLevel(ToolRiskLevel level) {
    return levels.entrySet().stream()
            .filter(entry -> entry.getValue() == level)
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
}
```

### 2.2 新增 Bean：`AgentLoopExecutorConfig`

```java
@Bean
public PauseStateStore pauseStateStore(@Qualifier("dataSource") DataSource dataSource) {
    return new JdbcPauseStateStore(dataSource);
}

@Bean
public ToolRiskRegistry toolRiskRegistry() {
    return ToolRiskRegistry.defaults();
}

@Bean
public PauseConfig pauseConfig(ToolRiskRegistry toolRiskRegistry, PauseStateStore pauseStateStore) {
    return new PauseConfig(toolRiskRegistry.toolsWithLevel(ToolRiskLevel.HIGH_RISK), pauseStateStore);
}
```

`agentLoopExecutorFactory(...)` 这个 `@Bean` 方法（原第 133-151 行）新增两个参数
`PauseConfig pauseConfig, ToolRiskRegistry toolRiskRegistry`，传给 `AgentLoopExecutorFactory` 的
构造函数（需要新增一个字段 + 一个 telescoping 重载，和 `AgentLoopExecutor` 那边同样的顾虑——
**先检查** `AgentLoopExecutorFactory` 有没有被测试直接 `new` 出来，如果有，同样不能改已有构造函数
签名，只能加新重载）。

### 2.3 `AgentLoopExecutorFactory` 接入

`buildExecutor`（原第 150-161 行）和 `forAnalytics`（原第 191-201 行）两处 `Builder` 链都加
`.pauseConfig(pauseConfig)`——和 Ticket 1 的 `.hooks(...)` 是完全一样的"两处都要改，容易漏一处"
的坑。

### 2.4 拒绝原因回传

`resume()` 已有的 `ResumeInstruction.ApprovalDecision(boolean approved, String rejectionReason)`
分支（`AgentLoopExecutor.java` 第 684-686 行）已经把拒绝原因喂回模型，这次不用改这部分逻辑——
只需要在审批页面（前端，不在这一票范围）能传 `rejectionReason`，后端接口层面确认
`POST /agent/{conversationId}/approve` 这类端点（如果还没有，需要新增；**先验证**现在有没有已经
存在的暂停恢复 HTTP 端点，`grep resume(` 确认调用点）。

## 3. 会话级 Budget 熔断

### 3.1 组件：`SessionBudgetTracker`（新增，`loop.hook` 包）

不能用 `RunContext`（一次 `stream()`/`resume()` 调用的生命周期，覆盖不了同一 `conversationId`
下**跨多轮用户提问**的累加）——需要一个和 `AgentTaskManager` 同级别、按 `conversationId` 长期
持有的组件：

```java
package com.agenttrail.loop.hook;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单会话 token 消耗的进程内累加器。**已知局限**：纯内存、不跨实例、进程重启清零——
 * 和 {@code InMemoryPauseStateStore} 是同一类局限，多实例部署下同一会话被路由到不同实例时
 * 预算会被各自独立计算，不是全局准确的。跨实例/跨自然日的准确聚合依赖 Ticket 4 的
 * {@code TraceStore} 生产数据，这次不做——单实例场景下这个局限不影响正确性。
 */
public class SessionBudgetTracker {

    private final Map<String, AtomicLong> totalsByConversation = new ConcurrentHashMap<>();
    private final long budgetPerSession;

    public SessionBudgetTracker(long budgetPerSession) {
        this.budgetPerSession = budgetPerSession;
    }

    /** @return 累加后的总量 */
    public long record(String conversationId, long promptTokens, long completionTokens) {
        return totalsByConversation
                .computeIfAbsent(conversationId, ignored -> new AtomicLong())
                .addAndGet(promptTokens + completionTokens);
    }

    public boolean overBudget(String conversationId) {
        AtomicLong total = totalsByConversation.get(conversationId);
        return total != null && total.get() > budgetPerSession;
    }

    /** 会话结束时清理，避免长期运行的进程里这个 Map 无限增长。 */
    public void forget(String conversationId) {
        totalsByConversation.remove(conversationId);
    }
}
```

### 3.2 接入 `AgentLoopExecutor`

**不通过 Ticket 1 的 `BudgetHook`**——那个接口是 void 观察型，没有能力让循环"熔断"。这里新增一个
独立的、决策型的可选依赖，和 `maxConsecutiveToolFailures` 走同一种"字段 + 检查点"模式，不是
Hook SPI 的一部分：

```java
// AgentLoopExecutor 新增字段（通过 Builder 注入，可选，null 表示不启用）
private final SessionBudgetTracker budgetTracker;
```

在 `finishRound()` 里，紧跟 Ticket 1 加的 `fireBudget(context, state)` 调用之后：

```java
if (budgetTracker != null) {
    budgetTracker.record(context.conversationId(), state.promptTokens(), state.completionTokens());
}
```

在**递归调用 `scheduleRound(context)` 之前**（原第 556 行，和 `maxConsecutiveToolFailures` 检查
同一个位置、检查顺序在它之后），新增：

```java
if (budgetTracker != null && budgetTracker.overBudget(context.conversationId())) {
    String message = "本会话 token 消耗已超过预算上限，本轮到此为止——如需继续，请开启新会话。";
    state.appendText(message);
    context.emit(new AgentStreamEvent.Text(message));
    completeRun(state, context);
    return;
}
scheduleRound(context);
```

`completeRun`/`failRun` 收尾时调用 `budgetTracker.forget(context.conversationId())`——
**先验证**：这个会话后续还会不会有新的用户提问进来复用同一个 `conversationId`（如果会，
`forget` 清零后下一轮提问的预算重新计满，等于预算是"按对话轮次"而不是"按会话"——需要确认
产品语义到底是哪一种，这里先按"每次完整推理结束就清零"实现，如果验证发现应该是会话级累计
不清零，去掉 `forget` 调用即可，不影响其余设计）。

### 3.3 Bean 装配

```java
@Bean
public SessionBudgetTracker sessionBudgetTracker(
        @Value("${agenttrail.budget.per-session-tokens:200000}") long perSessionTokens) {
    return new SessionBudgetTracker(perSessionTokens);
}
```

`buildExecutor`/`forAnalytics` 两处都加 `.budgetTracker(sessionBudgetTracker)`。默认阈值
20 万 token 是一个先给的起点值，不是校准过的数字，`Further Notes` 里记一下"这个数字需要拿
真实用量数据回头校正"。

## 4. Testing Decisions

- 审批测试：挂载一个 `write_file` 工具的对话请求模型写文件，验证走 `Paused` 事件而不是直接执行；
  `resume` 传 `approved=true` 后工具真正执行；传 `approved=false` 后模型收到拒绝原因，不执行
- 装配测试：`AgentLoopExecutorFactory` 生产装配（`@SpringBootTest` 加载完整上下文）里
  `PauseConfig`/`SessionBudgetTracker` 两个 Bean 都存在且非空，`buildExecutor`/`forAnalytics`
  各自产出的执行器都不再是"零个高危工具会被审批"的状态——可以用反射或者直接跑一次端到端对话
  触发 `write_file` 来验证，不要只测 Bean 存在
- Budget 测试：连续多轮问答，累计 token 超过阈值后下一轮直接收到熔断提示文本，不再发起模型调用
  （可以用 `ScriptedLlmClient` 之类的测试替身统计实际调用次数，断言超预算后调用次数不再增长）
- 并发测试：`SessionBudgetTracker.record` 在多线程下的原子性（`AtomicLong` 已保证，但要有一个
  测试实际并发调用验证没有丢计数）

## Out of Scope

- 跨会话/自然日的成本聚合与熔断（依赖 Ticket 4）
- 降级到更便宜模型
- 审批页面前端（前端 ticket，未拆）
- `SessionBudgetTracker` 的多实例一致性
