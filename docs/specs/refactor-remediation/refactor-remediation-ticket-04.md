# Ticket 04：AgentLoopExecutor 看门狗定时器泄漏修复 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

这一票**只改一件事**：`AgentLoopExecutor.scheduleRoundWatchdog` 里 `Mono.delay(roundTimeout).subscribe(...)`
正常轮次结束时从不释放，持有整个 `RunContext`（含消息列表）直到 8 分钟绝对超时自然触发。不改
`ToolCallExecutor` 的任何代码（它已经是正确实现，只作参照），不改 `LlmInvoker` 的两段式
TTFT/idle 超时（那是另一层、性质不同的超时保护，见 §2），不改超时之后的失败处理逻辑本身
（`failRun` 的行为不变）。

## 1. 现状代码

`AgentLoopExecutor.java`（**先验证**：以下行号是本票开工时的真实行号，实现前重新确认一次，
1206 行的大文件后续改动会让行号漂移）：

```java
// AgentLoopExecutor.java:670-696（scheduleRound 的订阅部分）
Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
        .publishOn(Schedulers.boundedElastic())
        .doOnNext(chunk -> { ...; processChunk(chunk, state, context); })
        .doOnComplete(() -> { stopTimer(totalSample, totalTimer); finishRound(state, context, requestSnapshot, skillTool); })
        .doOnError(error -> { stopTimer(totalSample, totalTimer); failRun(error, context, state, requestSnapshot); })
        .onErrorComplete()
        .subscribe();

taskManager.setDisposable(context.conversationId(), subscription);
scheduleRoundWatchdog(context, state, requestSnapshot, subscription);
```

```java
// AgentLoopExecutor.java:707-717
private void scheduleRoundWatchdog(RunContext context, RoundState state, String requestSnapshot,
                                   Disposable subscription) {
    Mono.delay(roundTimeout).subscribe(tick -> {
        if (subscription.isDisposed()) {
            return;
        }
        subscription.dispose();
        failRun(new TimeoutException("round exceeded absolute timeout of " + roundTimeout),
                context, state, requestSnapshot);
    });
}
```

方法上方的类注释把设计意图写得很明确（"踩坑点 #93"）：**这是绝对时钟兜底**——不看这一轮此刻
处于什么状态，只看 `roundTimeout` 之后 `subscription` 是不是还没结束。问题不在这个设计意图本身，
在于承载这个 `Mono.delay(...)` 的 `subscribe()` 调用**每轮都发生、且从不 dispose**：正常轮次
99% 的情况下会在几秒到几十秒内经由 `doOnComplete`/`doOnError` 结束，但 watchdog 的延迟任务不
知道这件事，会一直挂在调度器上直到 `roundTimeout`（默认 8 分钟）自然到期，到期后发现
`subscription.isDisposed()` 已经是 `true` 就直接返回——**这个"发现已经晚了"的动作本身，就是
泄漏在多轮对话下持续堆积的定时器/引用**。

## 2. 先验证：`ToolCallExecutor` 的写法，和为什么不能直接改成 `.timeout(roundTimeout)`

`ToolCallExecutor.java:133-155`：

```java
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector, ToolCallback sessionScopedTool,
                           Map<String, String> mdcSnapshot) {
    try {
        return Flux.fromIterable(toolCalls)
                .flatMapSequential(toolCall -> Mono
                        .fromCallable(() -> MdcPropagation.call(mdcSnapshot,
                                () -> executeOne(toolCall, emit, paramInjector, sessionScopedTool)))
                        .subscribeOn(TOOL_EXECUTION_SCHEDULER))
                .collectList()
                .timeout(roundTimeout)
                .block();
    } catch (RuntimeException failure) {
        if (!(failure.getCause() instanceof TimeoutException)) {
            throw failure;
        }
        // ... 按超时降级为错误结果喂回模型
    }
}
```

**关键区别，读代码才能发现，不能凭票面描述直接套用**：`ToolCallExecutor` 这里的 `.timeout(...)`
挂在 `.collectList()` **之后**——`collectList()` 把多元素 `Flux<ToolResponse>` 折叠成**只发一次
信号**的 `Mono<List<ToolResponse>>`。对只发一次终结信号的 `Mono`，`.timeout(Duration)` 等价于
"从订阅到这一次信号之间不能超过 `Duration`"，天然就是绝对时钟语义。

而 `scheduleRound` 里 `llmInvoker.streamRound(...)` 返回的是**持续吐 chunk 的 `Flux<ChatResponse>`**
（一轮里可能有几十上百个 chunk）。Reactor `Flux.timeout(Duration)` 的官方语义是"自上一次信号
（或订阅本身）起超过 `Duration` 还没有新信号就超时"——**每来一个新 chunk 这个窗口会重新起算**。
`LlmInvoker.java:56-57` 自己已经在用这个精确语义做另一件事（TTFT + 每 chunk 后的 idle 窗口），
证明这仓库里"per-chunk 重置的超时"和"绝对总时长超时"是两种保护对象不同、有意分开的机制。

**结论**：直接在 `Flux` 上加 `.timeout(roundTimeout)`，会把"绝对总时长超时"悄悄换成"per-chunk
不活动超时"——一个持续稳定吐字超过 8 分钟的健康长回答不会再被打断，这是**真实的行为语义变化**，
违反票面"不能改变超时后的行为语义"的要求，不能作为实现细节自己拍板。下面给两个选项，实现时
二选一，把选的是哪个和为什么写进 PR 描述。

## 3. 两个选项

### 选项 A：接受语义变化，直接用 `.timeout(roundTimeout)`

```java
Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
        .timeout(roundTimeout)
        .publishOn(Schedulers.boundedElastic())
        .doOnNext(chunk -> { ...; processChunk(chunk, state, context); })
        .doOnComplete(() -> { stopTimer(totalSample, totalTimer); finishRound(state, context, requestSnapshot, skillTool); })
        .doOnError(error -> {
            stopTimer(totalSample, totalTimer);
            failRun(error, context, state, requestSnapshot); // error 可能是 TimeoutException，走同一条 failRun 路径
        })
        .onErrorComplete()
        .subscribe();

taskManager.setDisposable(context.conversationId(), subscription);
// scheduleRoundWatchdog 整个方法删除，不再需要
```

好处：真正做到"挂在管道上、订阅结束自动跟着结束"，没有任何独立存活的定时器，最接近
`ToolCallExecutor` 的字面写法。代价：把"绝对 8 分钟硬顶"降级成"任意两个 chunk 之间不能间隔超过
8 分钟"，一轮总时长可以远超 `roundTimeout` 而不触发保护，只要模型持续在吐字。`failRun` 收到的
`error` 是 Reactor 抛出的 `TimeoutException`，消息文本和现在的 `"round exceeded absolute timeout
of " + roundTimeout` 不同（Reactor 默认消息类似 `"Did not observe any item or terminal signal
within ..."`），这条消息会原样进 `AgentStreamEvent.Error.message()` 和 `agent_trace.error_message`——
如果下游（前端展示、审计查询）对这个字符串有隐式依赖，需要一并检查。

### 选项 B：保留绝对超时语义，把 watchdog 的生命周期挂到主管道上（推荐）

不改 `roundTimeout` 的判定条件，只修复"泄漏"这一个 bug：让 watchdog 的 `Disposable` 在主订阅
结束时（无论成功、失败还是被 watchdog 自己取消）跟着释放，而不是放任它活到 `roundTimeout` 到期。

```java
private void scheduleRound(RunContext context) {
    // ... 不变，直到构造 subscription 之前

    AtomicReference<Disposable> watchdogRef = new AtomicReference<>();
    Disposable subscription = llmInvoker.streamRound(context.messages(), roundTools)
            .publishOn(Schedulers.boundedElastic())
            .doOnNext(chunk -> { ...; processChunk(chunk, state, context); })
            .doOnComplete(() -> { stopTimer(totalSample, totalTimer); finishRound(state, context, requestSnapshot, skillTool); })
            .doOnError(error -> { stopTimer(totalSample, totalTimer); failRun(error, context, state, requestSnapshot); })
            .onErrorComplete()
            // 主订阅一旦以任何方式终结（complete/error/cancel），立刻把 watchdog 的延迟任务也释放掉——
            // 这是这次修复真正要做的事：正常轮次不再让 Mono.delay 活到 roundTimeout 才发现自己没用了
            .doFinally(signalType -> {
                Disposable watchdog = watchdogRef.get();
                if (watchdog != null) {
                    watchdog.dispose();
                }
            })
            .subscribe();

    taskManager.setDisposable(context.conversationId(), subscription);
    watchdogRef.set(scheduleRoundWatchdog(context, state, requestSnapshot, subscription));
}

// 唯一改动：返回 Disposable 给调用方持有，其余判定逻辑原样不动
private Disposable scheduleRoundWatchdog(RunContext context, RoundState state, String requestSnapshot,
                                         Disposable subscription) {
    return Mono.delay(roundTimeout).subscribe(tick -> {
        if (subscription.isDisposed()) {
            return;
        }
        subscription.dispose();
        failRun(new TimeoutException("round exceeded absolute timeout of " + roundTimeout),
                context, state, requestSnapshot);
    });
}
```

好处：`failRun` 触发的条件、抛出的异常类型、异常消息文本、后续的落库/Hook/Error 事件完全不变——
真正做到"只改实现方式，不改行为语义"。代价：字面上仍然是一个独立 `subscribe()` 出来的
`Mono.delay(...)`，不是 `ToolCallExecutor` 那种纯 `.timeout()` operator 写法，但它已经不再是
"独立存活、正常路径下从不释放"的泄漏源——`doFinally` 保证了它的生命周期严格绑定在主订阅上，这才
是票面"泄漏"这个 bug 真正要修的东西。

**推荐选项 B**：这一票的标题和 `refactor-remediation.md` 里给出的问题描述都是"泄漏"，不是"超时
语义要改成 inactivity 模式"——选项 B 精确对症，选项 A 是捎带的语义变更，除非另有产品决策支持
放宽绝对超时保证，否则不建议顺带做。

## 4. Testing Decisions

- **正常结束不再残留订阅**：复用 `AgentLoopExecutor` 已有的"测试专用注入短 `roundTimeout`"构造
  函数（`AgentLoopExecutor.java:319` 附近）跑若干轮正常对话，每轮结束后验证没有残留的延迟任务；
  选项 B 下可直接对 `watchdogRef` 持有的 `Disposable` 断言 `isDisposed() == true`，验证 `doFinally`
  确实触发了
- **真实超时场景行为不变**：把 `roundTimeout` 设得比 `ScriptedChatModel` 制造的人为延迟短，触发
  真实超时，断言 `AgentStreamEvent.Error` 的 `code`/`message`（选项 B 应与修复前逐字节相同；选项
  A 下 `code` 相同、`message` 文本会变，需在测试里显式注明这是已知变化）、`taskManager` 里对应
  `conversationId` 的任务被清理、落库的 `agent_trace.success=false`
- 两个选项都要验证：watchdog 触发时若主订阅**已经**完成（正常竞态），不会重复调用 `failRun`——
  现有 `subscription.isDisposed()` 判断已经保证这一点，修复后保留同样的回归测试

## Out of Scope

- `LlmInvoker` 的 TTFT/idle 两段式超时——性质不同，不在这次修复范围
- `roundTimeout` 默认值本身要不要调整——这一票只管泄漏，不管超时时长是否合理
- 选项 A/B 之外的其它实现方式（比如引入 `TimerWheel`/自建定时任务管理器）——过度设计，`Reactor`
  自带的机制已经够用
