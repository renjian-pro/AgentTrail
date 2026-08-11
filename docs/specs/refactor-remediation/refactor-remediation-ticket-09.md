# Ticket 09：StructuredLlmCall 抽取，消除 5 处重复解析逻辑 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：抽取一个 `StructuredLlmCall` 工具类，统一"构造 `RunnableParams`（带
`OutputType`）→ `executor.call(...)` → `JsonRepair.fixJson` → 反序列化 → 异常包装"这套五处
重复的模式，五处调用点各自改成调这个新工具类。不改变任何一处的对外行为（同样的输入应该产出
同样的输出/同样的异常），纯内部实现重构。

证据见 `refactor-blueprint.md` §2.4 第一条："`RequirementStrategy.java:38-48`、
`SchemaStrategy.java:44-55`、`OutlineStrategy.java:46-57`、`DeepResearchService.java` 的
`critique`/`generatePlan` 各自独立实现同一套步骤……核心层已经为'同步 LLM 调用'抽象过一次
（`SynchronousLlmCall`，六处正确复用），但没有等价的'结构化调用+JsonRepair+解析'版本"。

## 1. 现有五处实现的真实差异（先验证结论）

**把五处现有实现真的读了一遍**，结论是：三处（`RequirementStrategy`/`SchemaStrategy`/
`DeepResearchService.generatePlan`）结构高度一致，`OutlineStrategy` 和
`DeepResearchService.critique` 各自多了一段不能被无脑合并的差异化逻辑：

| 调用点 | 位置 | 输出类型 | 解析失败时的行为 | 与"最大公约数"的差异 |
|---|---|---|---|---|
| `RequirementStrategy.execute` | `RequirementStrategy.java:38-48` | `PptRequirement` | `throw new PptGenerationException(...)` | 无 |
| `SchemaStrategy.execute` | `SchemaStrategy.java:44-55`（`OutputType`/`RunnableParams` 构造在 44 行，`executor.call` 在 47 行，try/catch 在 48-54 行） | `PptSchema` | `throw new PptGenerationException(...)` | 无 |
| `DeepResearchService.generatePlan` | `DeepResearchService.java:301-318` | `ResearchPlan` | `throw new IllegalStateException(...)` | **有 content-envelope 解包兜底**：`JsonRepair` 修不动时把原文包成 `{"content":"..."}`，`tryUnwrapPlainContentFallback`（`DeepResearchService.java:320-331`）先拆一层再解析一次，拆不开才真正抛异常 |
| `DeepResearchService.critique` | `DeepResearchService.java:250-271` | `CritiqueResult` | **不抛异常**——解析失败保守返回 `new CritiqueResult(false, "批判结果解析失败，本轮判定为不通过以确保继续迭代")`（`DeepResearchService.java:268-269`） | 同样有 content-envelope 解包兜底（`tryUnwrapCritiqueContentFallback`，`DeepResearchService.java:273-284`）；**且解析彻底失败时不抛异常，返回一个默认值** |
| `OutlineStrategy.execute` | `OutlineStrategy.java:45-57` | `PptOutline` | `throw new PptGenerationException(...)` | **有一整套约 290 行的宽松手写解析器**（`parseOutline`/`extractOutlineLenient`/`extractSimpleStringField`/`findKey`/`indexOfStructural`/`readStringValueLenient`/`extractSlidesLenient`/`findMatchingBracketLenient`/`parseSlideLenient`/`extractBulletsLenient`，`OutlineStrategy.java:78-367`），`JsonRepair.fixJson` 修不动时按字段名逐字符扫描原始字符串提取 |

**不要假设五处逻辑完全一样**——能统一的只是"构造带 `OutputType` 的 `RunnableParams` → 调
`executor.call(...)` → `JsonRepair.fixJson` → `MAPPER.readValue(...)`"这一段共同前缀，
`OutlineStrategy` 的宽松解析器和 `critique`/`generatePlan` 的 content-envelope 兜底都是各自
调用点独有的"解析失败后再试一次"逻辑，且失败时的最终行为（抛什么异常/要不要抛异常）三处
互不相同。

## 2. 设计：吸收共同前缀，差异部分留在调用点

参照 `SynchronousLlmCall.java`（`loop/core/`）的现有设计——静态工具类，不持有状态，只做
"调用 + 超时"这一步的封装，**同样是极薄的一层，不试图变成一个大而全的框架**。

```java
package com.agenttrail.loop.core;

import com.agenttrail.loop.model.OutputType;
import com.agenttrail.loop.model.RunnableParams;
import com.agenttrail.loop.structured.JsonRepair;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * "结构化 LLM 调用"的共同前缀——构造带 {@link OutputType} 的 {@link RunnableParams} 由调用方
 * 负责（不同调用点的 conversationId/stage 命名空间不同，见五处调用点各自的构造方式），这里
 * 只统一 {@code executor.call(...)} → {@link JsonRepair#fixJson} → 反序列化这一段。
 *
 * <p>解析失败时统一抛 {@link StructuredLlmCallException}（包装原始异常和原始 JSON 文本），
 * 不假设调用方想要什么异常类型——五处调用点里 {@code RequirementStrategy}/{@code SchemaStrategy}/
 * {@code OutlineStrategy} 想要 {@code PptGenerationException}，{@code
 * DeepResearchService.generatePlan} 想要 {@code IllegalStateException}，{@code
 * DeepResearchService.critique} 甚至不想抛异常而是返回一个默认值——这些差异化处理留在调用点，
 * 各自 catch {@link StructuredLlmCallException} 后按自己的语义处理，不在这个工具类里判断
 * "现在应该抛哪种异常"。
 */
public final class StructuredLlmCall {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredLlmCall() { }

    public static <T> T call(AgentLoopExecutor executor, String prompt, RunnableParams params, Class<T> type) {
        String rawJson = executor.call(prompt, params);
        String fixed = JsonRepair.fixJson(rawJson);
        try {
            return MAPPER.readValue(fixed, type);
        } catch (Exception malformed) {
            throw new StructuredLlmCallException(rawJson, fixed, malformed);
        }
    }

    public static final class StructuredLlmCallException extends RuntimeException {
        private final String rawJson;
        private final String fixedJson;

        StructuredLlmCallException(String rawJson, String fixedJson, Throwable cause) {
            super("结构化 LLM 调用解析失败: " + rawJson, cause);
            this.rawJson = rawJson;
            this.fixedJson = fixedJson;
        }

        public String rawJson() { return rawJson; }
        public String fixedJson() { return fixedJson; }
    }
}
```

**签名先验证结论**：五处调用点里 `RunnableParams` 的构造方式各不相同（`conversationId`/
`stage` 命名空间不同：PPT 三处用 `context.conversationId()` + `"ppt-generation"`，
DeepResearch 两处用 `UUID.randomUUID().toString()` + `"deepresearch"`，见
`DeepResearchService.freshParams`，`DeepResearchService.java:441-443`），所以
`StructuredLlmCall.call(...)` 不负责构造 `RunnableParams`，只接收调用方已经构造好的
`params`（含 `OutputType`）——这样避免这个工具类反过来要知道"conversationId 该怎么生成"
这种和结构化解析无关的业务细节。

## 3. 五处调用点改造后的样子

`RequirementStrategy.execute`（改造前 38-48 行十来行，改造后）：

```java
@Override
public PptGenerationContext execute(PptGenerationContext context) {
    RunnableParams params = new RunnableParams(context.conversationId(), "ppt-generation", Map.of(),
            OutputType.of(PptRequirement.class));
    try {
        PptRequirement requirement = StructuredLlmCall.call(
                executor, PptPrompts.REQUIREMENT + context.userRequirement(), params, PptRequirement.class);
        return context.withRequirement(requirement);
    } catch (StructuredLlmCall.StructuredLlmCallException malformed) {
        throw new PptGenerationException(
                "REQUIREMENT 状态解析失败，模型输出不是合法的 PptRequirement JSON: " + malformed.rawJson(), malformed);
    }
}
```

`SchemaStrategy.execute` 同构改造。

`DeepResearchService.generatePlan`（改造前 301-318 行，注意要**保留** content-envelope
解包兜底，`StructuredLlmCall` 抛出异常后才走这段兜底，不是替代它）：

```java
private ResearchPlan generatePlan(String topic) {
    RunnableParams params = freshParams(OutputType.of(ResearchPlan.class));
    try {
        return StructuredLlmCall.call(plainExecutor, DeepResearchPrompts.PLAN + topic, params, ResearchPlan.class);
    } catch (StructuredLlmCall.StructuredLlmCallException malformed) {
        ResearchPlan unwrapped = tryUnwrapPlainContentFallback(malformed.fixedJson());
        if (unwrapped != null) {
            return unwrapped;
        }
        throw new IllegalStateException(
                "执行计划解析失败，模型输出不是合法的 ResearchPlan JSON：" + malformed.rawJson(), malformed);
    }
}
```

`DeepResearchService.critique` 同构改造，`catch` 分支里先试 `tryUnwrapCritiqueContentFallback`，
拆不开时返回默认的 `CritiqueResult(false, ...)`（**不抛异常**，和现在行为一致）。

`OutlineStrategy.execute` **不建议改造成直接调用 `StructuredLlmCall.call(...)`**——它的解析
路径本身就不是"失败了再兜底"这种线性结构，而是 `parseOutline` 内部就有多层解包 +
`JsonRepair.fixJson` 只在最外层调用一次（`OutlineStrategy.java:79`），`StructuredLlmCall`
统一在拿到 `rawJson` 之后才做 `fixJson`+反序列化，和 `OutlineStrategy` 现有的"先
`fixJson`，反序列化失败前还要在 `parseOutline` 内部做多层 content-envelope 解包，解包失败
才落到 `extractOutlineLenient` 手写解析器"这套流程耦合太深，**先验证**：实现时应该判断
`OutlineStrategy` 是只改成"调用 `executor.call(...)` 拿到 `rawJson` 后原样传给自己现有的
`parseOutline` 方法"（不真正复用 `StructuredLlmCall` 的解析部分，只在"调用+异常包装"这一层
薄薄地对齐一下代码风格），还是保持现状完全不动——**这一票允许 `OutlineStrategy` 不参与统一**，
因为它的差异化逻辑量级（约 290 行）已经不是"重复代码"，是这个调用点独有的、有自己完整测试
覆盖的能力，勉强塞进通用抽象反而增加理解成本。如果最终判断"不改 `OutlineStrategy`"，这一票
的调用点数量从 5 处降到 4 处，需要在完成后同步更新 `refactor-remediation.md` 表格里 09 的
描述（如果那份文档需要更新——按顶层 Spec 的约定，状态变化记在那张表里，不是这份 ticket 里）。

## 4. Testing Decisions

- 五处调用点原有的单测（`RequirementStrategyTest.java`/`SchemaStrategyTest.java`/
  `OutlineStrategyTest.java`/`DeepResearchServiceTest.java`/`DeepResearchServiceIT.java`）
  改造后必须继续通过，不改变行为——重点验证：正常解析成功路径、`JsonRepair` 触发修复的路径、
  `generatePlan`/`critique` 的 content-envelope 解包兜底路径、`critique` 解析彻底失败时返回
  默认 `CritiqueResult` 而不是抛异常。
- 新增 `StructuredLlmCall` 自己的单测（新文件，`loop/core/StructuredLlmCallTest.java`），
  覆盖：
  - 成功解析：mock `AgentLoopExecutor.call(...)` 返回合法 JSON，验证反序列化结果正确。
  - `JsonRepair` 修复触发：mock 返回一段需要修复的"脏" JSON（比如尾逗号、未加引号的 key），
    验证修复后能正确解析。
  - 解析失败异常包装：mock 返回彻底无法解析的文本，验证抛出 `StructuredLlmCallException`，
    且 `rawJson()`/`fixedJson()`/`getCause()` 都能拿到预期值。
