# Ticket 10：GoldenCaseService 去重 + ToolCallExecutor 构造函数瘦身 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票两个独立的小改动，合并成一张票，可以分两次提交**：① `GoldenCaseService.create()`/
`update()` 的记录构造去重；② `ToolCallExecutor` 的 telescoping constructor + 3 个 `execute`
重载瘦身。两者互不依赖，可以任选顺序做。**不涉及** `AgentLoopExecutor`/
`AgentLoopExecutorFactory` 的 telescoping constructor（那两个类规模大得多，`refactor-blueprint.md`
§1.3/§1.7 已经把它们排进 Phase 3/Ticket 14，这里只处理 `ToolCallExecutor` 这一个小类）。

## 1. GoldenCaseService 去重

证据见 `refactor-blueprint.md` §2.4 第二条：`GoldenCaseService.create()`/`update()`
（`src/main/java/com/agenttrail/evaluation/GoldenCaseService.java:43-79`）完整重复了校验
`dimension`/`question`、构造 `GoldenCaseRecord`（含 `normalizeAssertions`/
`normalizeToolCalls`）的逻辑，只有 id/source/时间戳不同。

读了实际代码（`GoldenCaseService.java:43-79`），两个方法的差异点精确列出：

| | `create()` | `update()` |
|---|---|---|
| `id` 来源 | 新生成（传入为空则 `"promoted-" + UUID`）+ 检查是否与内建用例冲突/是否已存在 | 沿用路径参数 `id`，先 `repository.findById(id)` 找已存在记录，找不到抛 404 |
| `source` | 传入为空则默认 `GoldenCaseRecord.SOURCE_MANUAL` | 沿用 `existing.source()` |
| `sourceConversationId` | `request.sourceConversationId()` | 沿用 `existing.sourceConversationId()` |
| `createdAtMillis`/`updatedAtMillis` | 都是 `now` | `createdAtMillis` 沿用 `existing.createdAtMillis()`，`updatedAtMillis` 是新的 `now` |
| 共同部分 | `requireNonBlank(dimension/question)` + `normalizeAssertions`/`normalizeToolCalls` + 构造 `GoldenCaseRecord` 的其余 5 个字段（`asUser`/`referenceSql`/`assertions`/`expectedToolCalls`——顺序对应构造函数参数） | 同左 |

抽一个私有 `buildRecord(...)` helper，只吸收"共同部分"，id/source/时间戳这几个有差异的字段
继续由 `create`/`update` 各自决定后传进去：

```java
private GoldenCaseRecord buildRecord(String id, GoldenCaseRequest request, String source,
        String sourceConversationId, long createdAtMillis, long updatedAtMillis) {
    requireNonBlank(request.dimension(), "dimension");
    requireNonBlank(request.question(), "question");
    return new GoldenCaseRecord(id, request.dimension(), request.question(),
            request.asUser() == null ? "" : request.asUser(), request.referenceSql(),
            normalizeAssertions(request.assertions()), normalizeToolCalls(request.expectedToolCalls()),
            source, sourceConversationId, createdAtMillis, updatedAtMillis);
}

public GoldenCaseView create(GoldenCaseRequest request) {
    String id = (request.id() == null || request.id().isBlank())
            ? "promoted-" + UUID.randomUUID().toString().substring(0, 8) : request.id().trim();
    Set<String> builtinIds = builtinIds();
    if (builtinIds.contains(id)) {
        throw new ResponseStatusException(CONFLICT, "id 与内建用例冲突，内建用例只读: " + id);
    }
    if (repository.findById(id).isPresent()) {
        throw new ResponseStatusException(CONFLICT, "用例 id 已存在: " + id);
    }
    String source = request.source() == null || request.source().isBlank()
            ? GoldenCaseRecord.SOURCE_MANUAL : request.source();
    long now = System.currentTimeMillis();
    GoldenCaseRecord record = buildRecord(id, request, source, request.sourceConversationId(), now, now);
    repository.insert(record);
    return GoldenCaseView.ofRecord(record);
}

public GoldenCaseView update(String id, GoldenCaseRequest request) {
    GoldenCaseRecord existing = repository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                    builtinIds().contains(id) ? "内建用例只读，不能编辑: " + id : "用例不存在: " + id));
    GoldenCaseRecord updated = buildRecord(id, request, existing.source(), existing.sourceConversationId(),
            existing.createdAtMillis(), System.currentTimeMillis());
    repository.update(updated);
    return GoldenCaseView.ofRecord(updated);
}
```

**先验证**：`requireNonBlank` 校验放进 `buildRecord` 内部意味着校验时机从"方法开头"挪到
"构造 record 之前"——`create()` 里原本是先校验 dimension/question，再检查 id 冲突；改造后
变成先检查 id 冲突，`buildRecord` 内部才校验 dimension/question。如果调用方（前端/测试）依赖
"哪个校验错误先返回"这个顺序（比如同时传了冲突 id 和空 dimension，期望看到哪个错误），需要
确认 `GoldenCaseServiceTest.java` 有没有锁死这个顺序的测试；如果有，把
`requireNonBlank` 挪到 `create()`/`update()` 方法体最前面单独调用一次，`buildRecord` 内部
不重复校验（`buildRecord` 内部保留校验只是为了防御式编程，两种做法都能接受，取决于现有测试
的期望顺序）。

## 2. ToolCallExecutor 构造函数瘦身

证据见 `refactor-remediation.md` 第 9 行提到"`ToolCallExecutor` 构造函数瘦身"——**先验证
发现**：`refactor-blueprint.md` 正文里没有像 `GoldenCaseService` 那样给出 `ToolCallExecutor`
的详细 file:line 证据，只在 §9"推荐的落地顺序"第 5 条一句带过（"`ToolCallExecutor` 构造函数
瘦身（§2.4）——低风险机械改动"），且 §1.5/§1.3 都提到 `ToolCallExecutor` 但都是作为"做对了
超时/线程池"的正面例子，不是这里要修的问题。这个改动点的实际证据要靠直接读源码补齐，不是
从文档里抄来的——以下是读 `ToolCallExecutor.java`（`src/main/java/com/agenttrail/loop/core/`）
后的真实结构。

`ToolCallExecutor.java:80-96` 三个构造函数依次转发：

```java
ToolCallExecutor(List<ToolCallback> tools) {
    this(tools, null);
}
ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry) {
    this(tools, meterRegistry, DEFAULT_ROUND_TIMEOUT);
}
ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry, Duration roundTimeout) {
    this.toolsByName = tools.stream().collect(...);
    this.meterRegistry = meterRegistry;
    this.roundTimeout = roundTimeout;
}
```

`ToolCallExecutor.java:98-135` 三个 `execute` 重载依次转发到真正的实现（`133-155` 行的
5 参数版本）：

```java
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector) {
    return execute(toolCalls, emit, paramInjector, null, null);
}
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector, ToolCallback sessionScopedTool) {
    return execute(toolCalls, emit, paramInjector, sessionScopedTool, null);
}
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector, ToolCallback sessionScopedTool,
                           Map<String, String> mdcSnapshot) { /* 真正实现 */ }
```

规模比 `AgentLoopExecutor`（16 个构造函数）/`AgentLoopExecutorFactory`（12 个构造函数）小
得多，这里只是 3+3 共 6 个方法的 telescoping，"小型"这个判断属实。

**先验证：grep 调用点/子类，确认精简后不会破坏现有测试或生产装配**——结论：

- `ToolCallExecutor` 是包私有类（`class ToolCallExecutor`，无 `public` 修饰符），没有子类，
  只在 `loop.core` 包内被使用，改动范围完全封闭在这个包里。
- 唯一的生产调用点是 `AgentLoopExecutor.java:348`：
  `new ToolCallExecutor(withDeferredPool(tools, toolCatalog), meterRegistry)`——只用了
  2 参数构造函数，从未用过 3 参数版本（`roundTimeout` 参数在生产装配路径上从来没被覆盖过，
  始终是 `DEFAULT_ROUND_TIMEOUT`）。
- 测试文件 `ToolCallExecutorTest.java` 大量使用 1 参数构造函数（`new
  ToolCallExecutor(List.of(tool))`，出现十余次）、一次 2 参数版本（第 99 行，带
  `MeterRegistry` 验证指标）、一次 3 参数版本（用于注入短 `roundTimeout` 测试超时降级路径，
  对照 `ToolCallExecutor.java:88` 的注释"测试专用：注入一个短得多的 roundTimeout，不用真的
  等 5 分钟才能验证超时降级"）。
- `execute` 的四个重载里，**3 参数版本（`toolCalls, emit, paramInjector`）和 5 参数版本
  （带 `sessionScopedTool`/`mdcSnapshot`）都有生产调用点**（`AgentLoopExecutor.java:1001-1002`
  和 `1156-1157`/`1172-1173`），**唯独 4 参数版本（带 `sessionScopedTool` 但不带
  `mdcSnapshot`）grep 全仓库找不到任何调用方**（生产代码和测试代码都没有）——这是这次审查
  新发现的一处真正的死代码，瘦身时应该直接删除，不需要保留。

**瘦身方案**：构造函数收敛成一个规范构造函数 + 合理默认值处理（不改变外部调用方式——
`ToolCallExecutor` 是包内使用，"外部调用方式"实际指"包内其它类和测试代码继续能这样构造"）：

```java
ToolCallExecutor(List<ToolCallback> tools) {
    this(tools, null, DEFAULT_ROUND_TIMEOUT);
}

ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry) {
    this(tools, meterRegistry, DEFAULT_ROUND_TIMEOUT);
}

/** 测试专用：注入短 roundTimeout。生产装配从不覆盖这个参数。 */
ToolCallExecutor(List<ToolCallback> tools, MeterRegistry meterRegistry, Duration roundTimeout) {
    this.toolsByName = tools.stream().collect(...);
    this.meterRegistry = meterRegistry;
    this.roundTimeout = roundTimeout;
}
```

这三个构造函数本身规模已经不大（3 个，不是 16 个），**先验证后判断**：如果目标是"进一步减到
1 个规范构造函数"，需要把 `meterRegistry`/`roundTimeout` 都做成可选参数——Java 没有默认参数
语法，要么保留 2-3 个薄的重载（现状已经是这样，只是链条方向要保持"薄重载转发到唯一的完整
构造函数"，现状已经满足这一点），要么引入 Builder（对一个包私有的小类引入 Builder 有点
过度设计，参照 `refactor-blueprint.md` §1.6 对"通用抽象是否过重"的评估标准）。**结论：
`ToolCallExecutor` 的构造函数现状（3 个依次转发、无交叉分支）已经不是真正意义上的
"telescoping 混乱"，真正要瘦身的是 `execute` 的重载**——删掉无人调用的 4 参数版本，保留
3 参数（常见路径）和 5 参数（完整参数路径）两个，构造函数维持现状即可，不需要额外改动。

```java
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector) {
    return execute(toolCalls, emit, paramInjector, null, null);
}
// 删除：4 参数版本（sessionScopedTool 无 mdcSnapshot）——无调用方
List<ToolResponse> execute(List<ToolCall> toolCalls, Consumer<AgentStreamEvent> emit,
                           ToolParamInjector paramInjector, ToolCallback sessionScopedTool,
                           Map<String, String> mdcSnapshot) { /* 真正实现，不变 */ }
```

## 3. Testing Decisions

- `GoldenCaseServiceTest`：全部现有用例继续通过；新增 `buildRecord` 的边界测试——
  `dimension`/`question` 为空时无论走 `create` 还是 `update` 都应该抛 `BAD_REQUEST`（400）；
  如果第 1 节"先验证"发现校验顺序被现有测试锁定，改造后要保持同样的错误优先级。
- `ToolCallExecutorTest`：全部现有用例继续通过（1/2/3 参数构造函数、3/5 参数 `execute` 的
  测试用例都不用改调用方式）；确认删除 4 参数 `execute` 重载后编译不报错（说明确实没有
  隐藏调用方，包括测试文件里也没有）。
- 两处改动都是纯重构，不改变行为——不需要新增覆盖"新行为"的测试，只需要保证现有测试集
  100% 通过，外加一条 `buildRecord` 的边界测试。

## Out of Scope

- `AgentLoopExecutor`（16 个构造函数）/`AgentLoopExecutorFactory`（12 个构造函数）的
  telescoping constructor 瘦身——留给 Phase 3（Ticket 14），规模和风险都不是这张小票能吃下的。
- `ToolCallExecutor` 是否应该改成非包私有、暴露给其它包复用——不在这一票讨论范围，现状的
  包内可见性没有被质疑。
