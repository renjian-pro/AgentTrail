# Ticket 05：Skill 工具改为会话级缓存 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

这一票**只改 `SkillManager.buildSkillsTool()` 的调用频率**：现状是 `AgentLoopExecutor.scheduleRound()`
**每一轮**（不是每次对话开始）都调一次，做一次 JDBC 查询 + 逐个技能读盘解析 + 重建 `SkillsTool`
工具描述字符串。不改 `SkillManager` 的对账机制（`reconcile()`/`scheduledReconcile()`）、不改
`SkillController`/`SkillEnabledRequest` 的 HTTP 层、不改 `Skill.load()` 的磁盘解析逻辑本身。

## 1. 现状代码与设计意图

`AgentLoopExecutor.scheduleRound()`，`AgentLoopExecutor.java:649-655`：

```java
private void scheduleRound(RunContext context) {
    boolean toolsExhausted = maxRounds > 0 && context.nextRound() > maxRounds;
    // 每轮现取一次——技能中途被后台停用/启用，下一轮就是新状态，不用等新会话（见 SkillManager#buildSkillsTool）
    ToolCallback skillTool = (skillManager == null) ? null : skillManager.buildSkillsTool().orElse(null);
    List<ToolCallback> roundTools = toolsExhausted
            ? List.of() : withDiscoveredTools(context.toolSearchSession(), skillTool);
    ...
}
```

`SkillManager.java` 类注释和 `buildSkillsTool()`/`enabledSkills()` 方法注释把设计意图写得非常
明确，**先验证的结论就在这里，不用去猜**：

> 光有定时对账不够。运营在后台把某个技能停用，如果工具实例是启动时建好、之后一直复用的，那要
> 等到下次重启才生效。所以 `buildSkillsTool()` **每次对话请求都重新查一遍数据库**，按当下启用
> 的技能现装一个工具出来。**粒度细到每一次请求，运营点完开关下一轮立刻生效**。这也和循环内核
> "每轮重新组装工具列表"的设计是同一个思路——工具集合是每轮的输入，不是构造时固定死的字段。
>
> ——`SkillManager.java:27-32`

以及 `enabledSkills()` 方法注释（`SkillManager.java:78-85`）：

> "每次都重读磁盘"看着浪费，但它换来的是：**改完 SKILL.md 存盘，下一轮对话就是新内容**，既不用
> 重启也不用等定时对账。

**这里的"下一轮"和 `AgentLoopExecutor.scheduleRound` 调用点的注释原文一致，字面意思是同一次
`stream()` 调用内的下一个 round，不是"下一次新对话/新的 `stream()` 调用"**。也就是说，这不是
一个含糊的历史注释，是一个被反复重申、覆盖两个不同触发源（①通过 `setEnabled()` API 切换启用
状态、②直接改磁盘上的 `SKILL.md` 内容）的明确设计承诺：**同一场进行中的多轮对话，中途技能被
禁用/内容被改，最迟下一轮就必须反映出来**。

**这一票要解决的效率问题和这条设计承诺表面上冲突，但拆开粒度看冲突比想象的小得多**——见 §1.1。

### 1.1 关键澄清：这里的"按对话缓存"到底缓存多久

`RunContext` 是 `AgentLoopExecutor.stream(question, params)` 里 `new` 出来的
（`AgentLoopExecutor.java:588`），而 `stream()` 是**每条用户消息触发一次**（`AgentLoopController`
每收到一次 `POST /agent/v1/chat` 请求就调一次），不是整个多轮 `conversationId` 只调一次。

也就是说"按对话缓存"的真实作用域是**一次用户提问**（内部可能有多轮工具调用），不是"一整场
对话直到用户开新会话"。同一个 `conversationId` 里，用户发下一条消息，就是一次新的 `stream()`
调用、新的 `RunContext`，缓存自然失效重新查——**不需要用户开新会话，下一条消息就是新状态**。
真正"看不见更新"的窗口只有"这一条消息触发的多轮工具调用还没跑完"这几秒到几十秒，不是一整场
对话的量级。

### 1.2 决策：只做方案 A，不做 revision 失效钩子

**结论（2026-08-11 讨论后确认，不再是"两个方案二选一"）**：这一票只实现下面的方案 A。

依据：横向调查了 Skills 这套机制实际起源和几个主流框架的做法——Claude Code（这套机制的原型）、
已采纳同一套 SKILL.md 开放标准的 OpenAI Codex、LangChain 1.0 原生 Skills、CrewAI 的 Agent
Skills，**重建粒度全部停在"会话/请求开始时一次"，没有一个实现"运行中途热更新"这件事**——不是
技术做不到，是这个生态里从源头到所有下游实现都没把它当成一个需要解决的问题。AgentTrail 现在
"每一轮都重新查"这个设计，在它自己所模仿的整条参考链路（Claude Code 原型 → Spring AI 官方
`SkillsTool` 方案 → 生产参考实现的 DB 化改造 → AgentTrail）里找不到任何一环的先例，是本地
自己往前多走的一步，且没有被验证过是必要的。

如果后续真的出现一个具体场景——比如"运营发现某个技能有严重 bug，需要立刻全局熔断，等不及
用户的下一条消息"——那是一个新的、独立的"熔断"需求，应该做成 `AgentTaskManager`/取消广播那条线
上的强制中断机制，而不是让 Skill 工具的缓存策略去兼顾一个它本来不该管的紧急场景。这条留在
Out of Scope 里，不在这一票里预先设计。

## 1.3 为什么不做成 Hook 实现

AgentScope Java 的 `SkillHook`、Spring AI Alibaba 的 `SkillsAgentHook` 都把技能注入实现成一个
真实的 Hook——这被当作"要不要留着 `Hook` SPI"（见 Ticket 14 §3）的一个真实场景证据，但**不代表
这一票也要把 `SkillManager` 改成 Hook 实现**。两个参考框架的 `Hook` 契约能接收并修改事件对象
再传回（ASJ 是 `Mono<T> onEvent(T event)`），AgentTrail 的 `SessionStartHook` 是纯 `void` 观察
接口、`HookContext` 是只读 record，没有可写字段——把技能工具的构建塞进去要么违反"决策型逻辑不
进 Hook 契约"这条已有原则，要么 Hook 只能做旁路日志、真正干活的还是 `SkillManager`，和现状没有
本质区别。`SkillManager` 维持独立组件身份（和 `ToolRateLimiter`/`SessionBudgetTracker` 同一类角
色），详细论证见 Ticket 14 §3.1。

## 2. 实现

`RunContext` 是"一次完整推理（可能跨多轮）共享的运行时上下文"，`stream()` 里每次调用只 `new`
一次（`AgentLoopExecutor.java:588`），天然是"按对话"这个粒度的载体。加一个延迟初始化的缓存字段：

```java
// RunContext.java —— 在现有 record 的基础上加一个可变的缓存槽位，
// 和 toolTimeline/consecutiveToolFailures 一样，record 里放可变引用类型是这个类已有的写法
record RunContext(
        String question,
        RunnableParams params,
        List<Message> messages,
        Sinks.Many<AgentStreamEvent> sink,
        AtomicInteger roundCounter,
        long startTimeMillis,
        ToolSearchSession toolSearchSession,
        Map<String, String> mdcSnapshot,
        Map<String, ToolTimelineEntry> toolTimeline,
        Map<String, Integer> consecutiveToolFailures,
        AtomicReference<Optional<ToolCallback>> cachedSkillTool) {
    // 构造函数、其余方法不变，新增字段在原有的委托构造函数里初始化为 new AtomicReference<>(null)
}
```

```java
// AgentLoopExecutor.scheduleRound()
ToolCallback skillTool = resolveSkillTool(context);
```

```java
// AgentLoopExecutor 新增私有方法
private ToolCallback resolveSkillTool(RunContext context) {
    if (skillManager == null) {
        return null;
    }
    AtomicReference<Optional<ToolCallback>> cache = context.cachedSkillTool();
    Optional<ToolCallback> cached = cache.get();
    if (cached == null) {
        cached = skillManager.buildSkillsTool();
        cache.set(cached);
    }
    return cached.orElse(null);
}
```

**效果**：同一次 `stream()` 调用（一条用户消息触发的整场多轮工具调用）内不管跑多少轮，只在
第一轮触发一次 JDBC 查询 + 磁盘读取，后续轮次直接复用。下一条用户消息（哪怕在同一个
`conversationId` 里）会创建新的 `RunContext`，缓存自然失效，重新查一次——**不是"要等用户开
新会话"，是"下一条消息就生效"**（依据见 §1.1/§1.2）。

## 3. 必须同步改写的注释

`SkillManager.java:27-32` 类注释和 `enabledSkills()` 方法注释（`SkillManager.java:78-85`）
里"下一轮立刻生效"的措辞不再准确，必须同步改写，不能让代码注释继续描述一个实现已经不再
提供的保证。改写方向：把"下一轮"改成"下一次请求/下一条消息"，并可以引用本票 §1.2 的调研结论，
说明这是对齐 Skills 生态实际标准做法的有意选择，不是遗留的能力缺失。

`AgentLoopExecutor.scheduleRound()` 里的行内注释（`AgentLoopExecutor.java:649` 附近）同样要
从"每轮现取一次"改写成描述新的按 `RunContext` 缓存的行为。

## Testing Decisions

- **同一次 `stream()` 调用内多轮只查一次库**：用 mock/spy 的 `SkillRepository`（或者给
  `SkillManager` 包一层计数代理）跑一个会触发多轮工具调用的 `ScriptedChatModel` 脚本，断言
  `repository.findEnabled()`（或 `buildSkillsTool()` 本身）在整场多轮工具调用里只被调用一次
- **技能启停后的可见性**：验证同一次 `stream()` 调用（同一条消息触发的多轮工具调用）进行中看
  不到变化；验证同一个 `conversationId` 下一条新消息（新的 `stream()` 调用）能看到最新状态——
  不需要构造一个全新的 `conversationId` 来验证"生效"，这正是 §1.1 澄清的粒度
- `skillManager == null`（未装配技能系统）时 `resolveSkillTool` 直接返回 `null`，不触碰缓存
  逻辑，不产生 NPE

## Out of Scope

- `SkillReconciliation` 定时对账本身的实现——这一票不改对账频率或对账逻辑
- 技能内容（`SKILL.md` 正文）的独立缓存/失效策略——只处理"哪些技能启用"这一层，`Skill.load()`
  磁盘解析的调用频率跟随 §2 的缓存自然降低，不单独优化
- `SkillsTool`/`SkillNames`/`SkillController` 等周边类的改动
- **紧急熔断机制**（运营需要立刻掐断一个正在被使用的技能，不等下一条消息）——这是一个独立于
  缓存策略的需求，如果以后真的出现，应该做成 `AgentTaskManager`/取消广播那条线上的强制中断，
  不在这一票里预先设计（见 §1.2 结尾）
