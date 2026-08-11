# Ticket 07：DeepResearch 进度可见性（currentStep 字段） — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：给 DeepResearch 任务加一个粗粒度的 `currentStep` 字段，从后端
`DeepResearchTaskRegistry`/`DeepResearchService` 一路透到前端 `ResearchReportCard.vue` 已经
画好但纯装饰的四步流程图，让它真正跟随执行阶段高亮。**不做**逐 checkpoint/节点粒度的事件流
（那是 Phase 6 `WorkflowEvent` 的范围，见下面第 5 节的明确说明），**不做**任务持久化（见
Ticket 06，两票独立）。

证据见 `refactor-blueprint.md` §2.7 整节，结论原话："在 `DeepResearchTaskRegistry` 的任务
条目上加一个 `volatile currentStep` 字段（`CLARIFYING/PLANNING/SEARCHING/CRITIQUING/
SUMMARIZING`），`DeepResearchService` 现有的 `log.info` 调用点顺手多写一行更新该字段，
`DeepResearchTaskResponse` 加一个 `currentStep` 字段透出，前端把已经画好的四步图从装饰改成
真绑定"。

## 1. 后端：真实阶段划分（先验证结论）

读了 `DeepResearchService.java`（`src/main/java/com/agenttrail/capability/deepresearch/`）
180-437 行的 `planExecuteCritiqueLoop`/`critique`/`generatePlan`/`executeLayered`/
`executeLayerConcurrently`/`summarize`，实际阶段和现有 `log.info` 调用点对应关系如下：

| 阶段（枚举值） | 对应方法 | 现有日志点 |
|---|---|---|
| `CLARIFYING` | `research()`（139-145 行）判断是否需要澄清 | **没有现成日志点**（见下方说明） |
| `PLANNING` | `proceedFromTopic()` 生成主题（161-169 行）+ `generatePlan()`（301-318 行） | `log.info("DeepResearch 研究主题：{}", topic)`（163 行）；`log.info("DeepResearch 第 {} 轮执行计划：{} 个任务", round, ...)`（190 行，在 `planExecuteCritiqueLoop` 内） |
| `SEARCHING` | `executeLayered()`/`executeLayerConcurrently()`/`executeTask()`（348-418 行） | `log.info("DeepResearch 第 {} 层：{} 个任务并发执行", ...)`（356 行）；`log.info("DeepResearch 执行任务 {}（第 {} 层，第 {} 次尝试）...", ...)`（405 行） |
| `CRITIQUING` | `critique()`（250-271 行） | `log.info("DeepResearch 第 {} 轮批判：{}...", round, ...)`（202 行，在 `planExecuteCritiqueLoop` 内，`critique()` 调用返回之后） |
| `SUMMARIZING` | `summarize()`（429-435 行） | **没有现成日志点**——`summarize()` 内部只有一行 `plainExecutor.call(...)`，方法本身不打日志 |

**先验证发现的偏差**：Ticket 描述假设"五个阶段都能在已有 `log.info` 调用点旁边顺手更新
`currentStep`"，实际读代码后发现 `CLARIFYING`（`research()` 开头）和 `SUMMARIZING`
（`summarize()` 方法体）这两处**没有可依附的现成日志点**——`research()` 在调用
`plainExecutor.call(...)` 之前没有任何 `log.info`，`summarize()` 全程也没有。这两处只能选择：
(a) 直接在方法入口写 `currentStep = XXX`，不强行找一个日志点凑；或 (b) 顺手加一行新的
`log.debug`/`log.info` 作为字段更新的落脚点，代价是多了两行原本没有的日志。**建议按 (a)
处理**——字段更新的诉求是"状态可见"，不是"必须挂在已有日志调用旁边"，(a) 更符合这一票"最小
改动"的精神，且不违背"这一票不新增日志"的约束（新增日志是手段不是目的）。

`proceedFromTopic()` 里的主题生成（163 行日志）和 `planExecuteCritiqueLoop` 里的
`generatePlan`（190 行日志）都属于广义的"规划"阶段，两者共用 `PLANNING`，不需要为"主题生成"
单开一个阶段——五个阶段名是 `refactor-blueprint.md` 已经给出的既定选择，这一票不重新设计
阶段划分本身，只确认每个阶段该挂在代码的哪个位置。

## 2. 后端实现

`DeepResearchTaskRegistry.java` 的任务条目目前直接存 `DeepResearchTaskResponse`（一个
record，天然不可变），没有单独的"任务条目"内部类可以加 `volatile` 字段。**先验证**：record
不可变意味着"更新 currentStep"不能是"改字段"，只能是"整体替换 map 里的 value"——如果沿用
现有 `tasks.compute(taskId, (id, existing) -> ...)` 的写法（`complete()`/`fail()` 已经是这个
模式），加一个 `updateStep(long taskId, String currentStep)` 方法，用同样的
`compute`（终态任务不应该再被步骤更新覆盖，参照 `complete()`/`fail()` 已有的"CANCELLED 状态
不可被覆盖"检查）：

```java
void updateStep(long taskId, String currentStep) {
    tasks.computeIfPresent(taskId, (ignored, existing) ->
            DeepResearchTaskResponse.RUNNING.equals(existing.status())
                    ? existing.withCurrentStep(currentStep) : existing);
}
```

`DeepResearchTaskResponse`（`web/dto/DeepResearchTaskResponse.java:14`）加 `currentStep`
字段：

```java
public record DeepResearchTaskResponse(long taskId, String status, DeepResearchReport report,
        String errorMsg, String currentStep) {
    // running()/success()/failed()/cancelled() 四个静态工厂都要补上 currentStep 参数
    // （running() 初始值建议 CLARIFYING；success/failed/cancelled 不再变化，可以保留调用时的最后值
    // 或置空，两种都能接受，选一种并在 record 的 javadoc 里写清楚）
    public DeepResearchTaskResponse withCurrentStep(String currentStep) {
        return new DeepResearchTaskResponse(taskId, status, report, errorMsg, currentStep);
    }
}
```

**这是一个新增字段的 record，改动会牵连所有构造 `DeepResearchTaskResponse` 的地方**——
先验证：grep `DeepResearchTaskResponse.running(`/`.success(`/`.failed(`/`.cancelled(` 的全部
调用点（目前已知在 `DeepResearchTaskRegistry.java` 内部），确认新增参数不会漏改。

`DeepResearchService` 需要能把 `currentStep` 更新回传给调用方（`DeepResearchController`）——
但 `DeepResearchService` 本身不持有 `taskRegistry`（`taskRegistry` 是 `DeepResearchController`
私有字段），当前调用链是 `DeepResearchController.research()` 提交一个 `Runnable` 给后台
executor，`Runnable` 内部调 `deepResearchService.research(...)`。**这是需要实现时决定的接入
方式**：`DeepResearchService` 的公开方法（`research`/`continueAfterClarification`）需要新增
一个"进度回调"参数（比如 `Consumer<String> onStepChange`，5 个私有方法内部在对应位置调用
`onStepChange.accept("PLANNING")` 等），`DeepResearchController` 传入
`step -> taskRegistry.updateStep(taskId, step)` 作为这个回调——这样 `DeepResearchService`
不需要依赖 `web.controller` 包（保持业务层不反向依赖 Web 层，和 `refactor-blueprint.md` §2.3
"业务直接依赖 Runtime"是同类问题，这里不应该在小票里引入一个新的反向依赖）。回调传 `null`
时保持现有测试（`DeepResearchServiceTest`/`DeepResearchServiceIT`）不需要改动调用方式，
只需要给 `research`/`continueAfterClarification` 加重载或默认参数。

## 3. DTO 与 Controller

`DeepResearchController.status()`（`web/controller/DeepResearchController.java:91-101`）不需要
改动——它只是把 `taskRegistry.find(taskId)` 原样返回，`currentStep` 字段会自动跟着
`DeepResearchTaskResponse` 一起序列化出去。

## 4. 前端

`frontend/src/api/research-api.ts` 的 `ResearchTask` 类型（第 21 行）加字段：

```ts
export type ResearchTask = {
  taskId: number
  status: ResearchTaskStatus
  report: DeepResearchReport | null
  errorMsg: string | null
  currentStep: ResearchStep | null   // 新增
}
export type ResearchStep = 'CLARIFYING' | 'PLANNING' | 'SEARCHING' | 'CRITIQUING' | 'SUMMARIZING'
```

`frontend/src/components/ResearchReportCard.vue` 第 49 行的静态流程图：

```html
<div class="research-flow"><span>规划</span><i /><span>检索</span><i /><span>验证</span><i /><span>综合</span></div>
```

只有四步（规划/检索/验证/综合），后端是五个阶段（`CLARIFYING` 多一个）——**先验证**：
`CLARIFYING` 阶段目前发生在 `ChatView.vue` 提交请求之后、`entry.result?.needsClarification`
分支处理之前（`ResearchReportCard.vue:53-57` 已经有单独的"需要补充信息"卡片），这个环节在
UI 上原本就不算进"规划→检索→验证→综合"这四步展示里，所以 `CLARIFYING` 阶段不需要在这个
流程图里高亮，只需要处理 `PLANNING`/`SEARCHING`/`CRITIQUING`/`SUMMARIZING` 四个到四个
`<span>` 的映射即可，不用为流程图新增第五个节点。

改造成真绑定（`entry.result` 目前是 `DeepResearchReport`，还没有 `currentStep`——需要
`ResearchEntry`/轮询链路把 `ResearchTask.currentStep` 也存到 `entry` 上，参照
`stores/chat.ts` 里 `ResearchEntry` 类型定义和 `ChatView.vue:165` 的 `applyResearchTask`
补一个字段）：

```html
<div class="research-flow">
  <span :class="{ active: entry.currentStep === 'PLANNING' }">规划</span><i />
  <span :class="{ active: entry.currentStep === 'SEARCHING' }">检索</span><i />
  <span :class="{ active: entry.currentStep === 'CRITIQUING' }">验证</span><i />
  <span :class="{ active: entry.currentStep === 'SUMMARIZING' }">综合</span>
</div>
```

`.active` 的具体样式（高亮色/加粗等）不在这一票的范围里做视觉设计决策，跟随
`ResearchReportCard.vue` 现有 CSS 的既有配色即可，找到已有的 `.research-loading`/
`.research-status` 相关样式做一致性延续。

轮询频率确认：`frontend/src/views/ChatView.vue:126-135` 的 `pollUntilTerminal` 默认
`intervalMs = 1500`（1.5 秒），DeepResearch（`ChatView.vue:156-159`）和 PPT（`ChatView.vue:
188-191`）共用这套轮询写法。这个频率决定了前端最多每 1.5 秒才能看到一次 `currentStep`
变化——阶段切换比这更快（比如 `SEARCHING` 阶段里多个任务并发几秒内跑完）时，前端不会看到
每一次子步骤，只会看到轮询命中时刻的最新阶段，这是可接受的粒度损失，不是这一票要解决的问题
（真正的逐事件粒度是 Phase 6 的范围）。

## 5. 明确的范围声明

这是**独立于 Phase 6（Ticket 17，DeepResearch 改造为正式 Workflow+Task）的临时方案**。
Phase 6 落地后，`currentStep` 这个粗粒度字段会被更完整的 `WorkflowEvent` 事件流取代
（`refactor-blueprint.md` §2.8 的 `GET .../{taskId}/events` 端点），不需要现在就把这个字段
设计成永久架构——`DeepResearchTaskRegistry`/`DeepResearchService` 的回调参数这些接入点在
Phase 6 重构时预期会被整体替换掉，不需要为"向前兼容 Phase 6"做额外设计。

## 6. Testing Decisions

- 后端：`DeepResearchServiceTest`/`DeepResearchServiceIT` 补一个测试，传入一个记录回调调用
  顺序的 `Consumer<String>`，验证一次完整的 `research()` 执行过程中 `currentStep` 按
  `PLANNING → SEARCHING → CRITIQUING（如果批判不通过会重复 PLANNING→SEARCHING→CRITIQUING）
  → SUMMARIZING` 的顺序推进，不出现跳步或乱序。
- `DeepResearchTaskRegistryTest` 补一个测试验证 `updateStep()` 对 `RUNNING` 状态任务生效、
  对已终态（`SUCCESS`/`FAILED`/`CANCELLED`）任务不生效（不能让一个迟到的步骤更新覆盖已经
  结束的任务状态，参照现有测试对 `CANCELLED` 状态的保护逻辑）。
- 前端：`ResearchReportCard.vue` 补一个组件级单测（Vitest + Vue Test Utils），传入不同
  `currentStep` 的 `entry`，断言对应 `<span>` 的 class 列表包含 `active`，其余三个不包含。

## Out of Scope

- Phase 6 的完整 `WorkflowEvent` 事件流设计——这一票只做粗粒度轮询字段。
- `currentStep` 精确到"第几层检索""第几轮批判"这种子粒度——五个阶段名之内不再细分。
- Skills 调用进度可见性的类似问题（`refactor-blueprint.md` §2.7 表格里提到的"工具名固定显示
  成 Skill"）——不是这一票的范围，两者虽然都是"进度可见性"问题但互相独立。
