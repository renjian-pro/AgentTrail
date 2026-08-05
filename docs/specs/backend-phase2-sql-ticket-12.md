# Ticket 12／18：calculate 工具 + DataAgent 装配接线 — 技术开发文档

> GitHub issue: [#58](https://github.com/renjian-pro/AgentTrail/issues/58)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.8／5.9 节 + [ADR-0003](../adr/0003-agentscope-isolated-data-agent-runtime.md)。`Blocked by` Ticket 7/8/9/11（需要全部工具都已存在）。**这一票是 Phase 2B 的收口票**——做完之后 DataAgent 才第一次能被用户真正调用。
>
> 前面五张票各自产出了零散的 `ToolCallback`，但**一个都没挂到执行器上**。这一票把它们装配起来、写 SKILL.md、接 HTTP 入口。

## 0. 范围边界

**这一票只做**：`calculate` 工具 + 数据分析 Skill 文档 + 把六个工具注册成延迟工具 + 一个 DataAgent 专用执行器 + HTTP 入口接线。

**这一票不做**：
- Golden Tasks 评测（Ticket 13）
- 前端（Ticket F4/F5）
- 修改任何已交付工具的内部逻辑

## 1. 前置事实（先读代码，这一票 80% 是在已有装配上做加法）

- `AgentLoopExecutorFactory`（`web/AgentLoopExecutorFactory.java`）已有 `forModel`/`forModelWithCharts`/`forInternalOrchestration` 三个入口，构造函数是逐参数叠加的重载链（目前最多 8 个参数）
- `AgentLoopExecutor.Builder`（`loop/core/AgentLoopExecutor.java:234-284`）有 `toolCatalog(ToolCatalog)` 方法——**这就是延迟工具的挂载点**
- `ToolCatalog.of(ToolSearchConfig config, List<ToolCallback> deferredTools, ChatModel chatModel)`（`loop/tools/search/ToolCatalog.java:40`）
- `ToolSearchConfig.defaults()` 已存在，Mode 枚举含 HYBRID
- `AgentLoopController.chat()`（`web/AgentLoopController.java:50-63`）当前固定调 `forModelWithCharts(modelId, webSearchEnabled)`
- Skills 机制已完整：`SkillManager` 从 `${agenttrail.skills.directory}` 加载，`SkillsTool` 把 frontmatter 渲染进自己的 description
- `AgentChatRequest`（`web/AgentChatRequest.java`）是请求体 record

## 2. 验收标准

- [ ] `calculate` 工具：`{"expression":"round((a-b)/b*100,2)","variablesJson":"{\"a\":3298,\"b\":3105}"}` 返回 `6.22`
- [ ] `calculate` 拒绝任何非数学表达式（尝试注入系统调用/文件路径时返回错误而不是执行）
- [ ] `skills/data-analysis/SKILL.md` 存在，`SkillManager` 能加载到它，`SkillsTool` 的 description 里出现它
- [ ] `POST /agent/v1/chat` 带 `mode=analytics` 时，返回的执行器工具列表里**有** `tool_search`，且六个分析工具都在延迟池里
- [ ] `mode` 不是 `analytics` 时，行为和现在完全一致（回归验证：现有对话/文件/图表功能一个都不能坏）
- [ ] **DataAgent 执行器的工具列表里没有 `bash`/`read_file`/`write_file`/`edit_file`/`grep`**（写一个断言测试，见第 6 节）
- [ ] 端到端冒烟：用 `analyst_test` 账号发一句"查一下租赁总量"，SSE 流里能看到 `tool_search` → `list_tables` → `execute_sql` 的调用序列，最终返回一个数字
- [ ] 分析库未配置（`agenttrail.analytics.datasource.enabled=false`）时，`mode=analytics` 请求返回一个明确的"分析能力未启用"错误，**不是 500 或 NPE**

## 3. `calculate` 工具

### 3.1 定位（必须写进工具 description 和类 Javadoc）

**只做最终标量公式计算**，不查数据库、不做批量聚合。批量聚合永远交给 SQL 的 `GROUP BY` + `SUM`/`AVG`；`calculate` 处理的是聚合完之后的那一步——环比、占比、贡献度、加权平均这类把几个标量代进一个公式的计算。

两个不这么做的理由：
1. **不让模型自己算**：LLM 本质是文本预测，多变量 + 多次四舍五入的算术很容易出错，而且错得很隐蔽（数字看起来很合理）。
2. **不让它写脚本用 Bash 跑**：这是真实教训——早期做法是让模型写 Python 脚本通过 Bash 执行，一是烧大量 token，二是模型会顺手**用 Bash 直接连数据库跑探测查询**，完全绕开前面辛苦搭的校验/权限/脱敏整套工具栈。给 Agent 一个通用工具，等于给了它一条绕过所有治理措施的路。

### 3.2 实现

用轻量表达式引擎，**不要**用 `ScriptEngine`/JavaScript/Groovy（那些能执行任意代码，等于开了一个新的绕过口）。推荐 exp4j：

```xml
<dependency>
    <groupId>net.objecthunter</groupId>
    <artifactId>exp4j</artifactId>
    <!-- 版本查 Maven Central 最新稳定版 -->
</dependency>
```

exp4j 只支持数学运算符和内置函数，**没有任何系统调用/反射/IO 能力**——这是选它的核心理由，不是因为它轻量。

```java
private String calculate(String expression, String variablesJson) {
    Map<String, Double> variables = parseVariables(variablesJson);   // Jackson 解析
    ExpressionBuilder builder = new ExpressionBuilder(expression)
            .variables(variables.keySet())
            .function(ROUND);           // exp4j 内置没有 round(x, n)，见下
    Expression exp = builder.build().setVariables(variables);
    ValidationResult validation = exp.validate();   // exp4j 自己的校验
    if (!validation.isValid()) {
        return "Error: 表达式无效：" + String.join("；", validation.getErrors());
    }
    double result = exp.evaluate();
    return formatResult(result);
}

// exp4j 内置函数不含两参数的 round，自己补一个——百分比/金额场景几乎每次都要用
private static final Function ROUND = new Function("round", 2) {
    @Override public double apply(double... args) {
        return BigDecimal.valueOf(args[0])
                .setScale((int) args[1], RoundingMode.HALF_UP).doubleValue();
    }
};
```

`formatResult`：用 `BigDecimal.valueOf(result).stripTrailingZeros().toPlainString()`，避免返回 `6.220000000000001` 或 `6.22E0` 这类形态给模型。

### 3.3 工具定义

```java
new JsonToolCallback("calculate", """
        计算一个数学表达式，用于环比、占比、增长率这类最终的标量公式计算。

        用法：先用 execute_sql 把需要的数值聚合出来（SUM/COUNT/AVG），
        再把这几个数字作为变量代进公式。

        示例：算环比增长率
          expression: "round((current - previous) / previous * 100, 2)"
          variablesJson: {"current": 3298.5, "previous": 3105.0}

        不要用它做批量数据的聚合——那是 SQL 的工作，把几千行数据塞进这里
        既算不了也没有意义。也不要自己心算：多变量和四舍五入很容易算错。
        """,
        """
        {"type":"object","properties":{\
        "expression":{"type":"string","description":"【必填】数学表达式，支持 + - * / ^ 和 round(x,n)/abs/sqrt/log 等函数"},\
        "variablesJson":{"type":"string","description":"【必填】变量取值的 JSON 对象，如 {\\"a\\":1,\\"b\\":2}"}},\
        "required":["expression","variablesJson"]}""",
        args -> calculate(args.text("expression"), args.text("variablesJson")));
```

## 4. 数据分析 Skill（`skills/data-analysis/SKILL.md`）

放在 `${agenttrail.skills.directory}` 配置指向的目录下（去 `application.yml` / `application-local.yml` 确认实际路径，**不要假设是 `src/main/resources/skills`**）。

结构对齐项目已有的 Skill（先看一眼现有 SKILL.md 的 frontmatter 格式再动手）：

```markdown
---
name: data-analysis
description: 用自然语言查询业务数据库并给出分析结论。涉及销售、租赁、客户、金额、部门业绩等数据问题时使用。
---

# 数据分析 SOP

这是一份行动指南，不是必须逐条执行的流程——按当前问题的实际需要跳步、回退、重复。

## 1. 先规划
问题涉及多个指标、多个时间窗口、需要图表或报告、或预计要跑多次查询时，
先调用 TodoWrite 建立任务清单，再开始执行。

## 2. 探索 Schema（不要跳过）
list_tables 看全貌 → 挑出本次真正需要的表 → describe_tables 展开这几张表的字段。
不要凭表名猜字段，不要一次把所有表都传给 describe_tables。

## 3. 消除歧义（这一步最容易被跳过，但错得最贵）
问题里出现"活跃客户""高价值""VIP""最近N个月"这类词时，先 lookup_glossary 查权威口径。
查不到时，用 execute_sql 跑一条探针查询确认真实数据形态
（SELECT DISTINCT <字段> ... LIMIT 10 / SELECT COUNT(*) ...），
或者在回答里明确说明你采用的口径让用户确认。绝不要自己编一个口径直接算。

**时间口径特别注意**：本数据集是历史快照，不是实时库。
"最近30天"必须基于数据集自身的最大时间算，不能用 NOW()/CURDATE()——
用了会查出 0 行。先 lookup_glossary 查时间锚点规则。

## 4. 生成并执行 SQL
复杂 SQL 可以先 validate_sql 预检（省一次数据库往返），简单的直接 execute_sql。

**不要自己写 dept_id / user_id 的权限过滤条件**——系统会自动按当前用户的数据范围
注入过滤，你写了也会被服务端覆盖。你只需要关心业务逻辑本身。

## 5. 验证结果（拿到数字不等于拿到答案）
- 数量级合理吗？（总租赁量应该是万级，如果返回 3 位数，大概率 JOIN 或条件写错了）
- 有没有 JOIN 放大？（一对多 JOIN 后 SUM 会重复计数——按主表主键 COUNT(DISTINCT) 交叉验证一下）
- 空结果不等于"没有数据"，按工具返回的提示逐条排查原因

## 6. 复杂计算
批量聚合用 SQL（GROUP BY + SUM/AVG）。
最终的公式计算（环比、占比、增长率）用 calculate 工具，不要自己心算。

## 7. 可视化
需要图表时用图表工具，返回的是一段 URL，直接写进 markdown 即可。

## 硬性禁止
- **任何情况下都不要使用 bash 工具**。不要通过 shell 连接数据库、不要写脚本执行查询、
  不要用命令行工具处理数据。所有数据访问必须走 execute_sql——
  它承载了安全校验、数据权限和脱敏，绕过它等于绕过全部治理。
- 不要在 SQL 里写 dept_id/user_id 的过滤条件（见第 4 节）。
- 不要从结果预览的前 20 行里手工计算总量或平均值。
```

> **注意**：最后那条"不要使用 bash"写在 SKILL.md 里是**第二道防线**，不是唯一防线。真正的保证是第 6 节——DataAgent 执行器压根不挂 Bash 工具。提示词约束和运行时隔离都要有：提示词让模型知道边界，运行时保证它越不过去。

## 5. 延迟工具注册

六个分析工具全部注册为**延迟工具**（`tool_search` 按需发现），不是常驻工具：

| 工具 | 挂载方式 |
|---|---|
| `list_tables` / `describe_tables` / `lookup_glossary` / `validate_sql` / `execute_sql` / `calculate` | 延迟池 |
| `tool_search` / `TodoWrite` / `SkillsTool` | 常驻（框架已有） |
| 图表工具 | 沿用现有的 `ChartToolProvider` 挂载方式 |

理由：六个工具的 description 加起来很长（`execute_sql` 和 `lookup_glossary` 的描述都不短），常驻会占掉大量上下文，而普通聊天场景一个都用不上。延迟披露后普通对话零成本。

**注意 `ToolSearchSession` 的"只增不减"语义**（`loop/tools/search/ToolSearchSession.java` 已实现）：数据分析场景一旦搜到 `list_tables`，后面大概率连着要用 `describe_tables`/`execute_sql`，发现后在整个请求生命周期内保留，不会用完就收回——否则模型会反复调 `tool_search` 搜同一批工具，白白浪费轮次。这是已有机制，这一票不用改，但要理解它为什么对这个场景特别重要。

## 6. 执行器装配

### 6.1 新增 `forAnalytics` 入口

在 `AgentLoopExecutorFactory` 上加一个方法（**不要**改现有三个入口的行为）：

```java
/**
 * DataAgent 专用执行器：工具列表是白名单式的，只有分析相关工具 + 图表，
 * 刻意不含 Bash/FileSystem/Grep——SKILL.md 里的"禁止用 bash"只是提示词约束，
 * 模型不可能每次都遵守；真正的隔离是这里压根不挂那些工具。
 * 这是"给 Agent 一个工具就等于给了它一条绕过治理的路"这条原则的落地点。
 */
public AgentLoopExecutor forAnalytics(String modelId) {
    if (analyticsToolProvider == null) {
        throw new IllegalStateException("分析能力未启用");   // 由 Controller 转成 4xx
    }
    // 缓存键只有 modelId（分析工具不像 webSearch 那样有开关）
    ...
    ToolCatalog catalog = ToolCatalog.of(
            ToolSearchConfig.defaults(),
            analyticsToolProvider.deferredTools(),   // 六个分析工具
            model.chatModel());
    return AgentLoopExecutor.builder(model.chatModel(), residentTools, 20)   // maxRounds 见 6.3
            .taskManager(taskManager)
            .thinkingMode(model.thinkingMode())
            .persistenceHook(persistenceHook)
            .toolCatalog(catalog)
            .contextPolicy(ContextPolicy.builder()
                    .protectedTools(chartToolNames)   // 图表 URL 不压缩，沿用现有做法
                    .build())
            .build();
}
```

新增一个 `AnalyticsToolProvider`（`capability/analytics/AnalyticsToolProvider.java`）把六个工具收口成一个 `List<ToolCallback> deferredTools()`，这样 `AgentLoopExecutorFactory` 的构造函数只多一个参数，不用一次塞六个。

`analyticsToolProvider` 为 null 表示这套装配不提供分析能力（对应 `agenttrail.analytics.datasource.enabled=false`），和现有 `webSearchToolProvider`/`chartToolProvider` 传 null 的处理方式保持一致。

### 6.2 `maxRounds` 要调大

现有装配是 `AgentLoopExecutor.builder(chatModel, tools, 10)`——**10 轮对数据分析不够**。一次典型的分析是：`tool_search` → `list_tables` → `describe_tables` → `lookup_glossary` → `validate_sql` → `execute_sql`（可能失败重写 1-2 次）→ `calculate` → 图表，轻松超过 10 轮。给 **20**。

同时注意 Ticket 11 的瞬态重试是在工具内部做的，不消耗轮次。

### 6.3 工具白名单断言测试

```java
@Test
void analyticsExecutorMustNotExposeShellOrFilesystemTools() {
    // 断言 forAnalytics 返回的执行器（常驻 + 延迟池全部）里
    // 不含 bash / read_file / write_file / edit_file / list_files / glob / grep
    // 这是一个安全断言，不是普通功能测试——它防的是未来某次重构顺手把
    // 通用工具集加进来这类事故
}
```

## 7. HTTP 入口接线

### 7.1 `AgentChatRequest` 加一个字段

```java
// 现有 record 加一个字段。用显式的 mode 字段而不是"根据消息内容猜是不是数据分析问题"——
// 意图识别放前端/用户手上，后端不做自然语言语义判断（和 PPT/DeepResearch 的
// 固定标记做法是同一个原则：不做语义解析，只认显式信号）
public record AgentChatRequest(..., String mode) { }
```

`mode` 取值：`null`/空 → 现有行为；`"analytics"` → DataAgent。

### 7.2 `AgentLoopController.chat()` 分支

```java
AgentLoopExecutor executor = "analytics".equals(request.mode())
        ? executorFactory.forAnalytics(request.modelId())
        : executorFactory.forModelWithCharts(request.modelId(), request.webSearchEnabled());
return executor.stream(request.message(), params).map(...);
```

`params` 完全不用改——`RunnableParams(conversationId, userId, Map.of("userId", userId, "conversation_id", conversationId), null)` 已经把 `userId` 放进 `toolParams` 了（`AgentLoopController.java:54-56`），`execute_sql` 的 `inputSchema` 声明了 `userId` 就能自动收到。**这一票不需要碰 `RunnableParams` 或 `ToolParamInjector`。**

### 7.3 分析能力未启用时的错误处理

`forAnalytics` 抛的 `IllegalStateException` 要被转成一个明确的 HTTP 4xx + 可读消息（"数据分析能力未启用，请联系管理员配置分析数据库"），不能让它变成 500 或者在 SSE 流里抛一个裸异常。看项目现有的异常处理方式（`auth/exception/` 下有先例），照着加。

## 8. 用户上下文脱敏检查（Ticket 11 第 6.4 节留的尾巴）

如果这一票的装配会把当前用户的档案信息（部门、角色、姓名等）拼进 system prompt，**检查一遍这段信息里有没有敏感字段**（身份证、住址、手机号）。有的话必须先过 `SensitiveFilter.maskValue`。

当前 Phase 2A 的 `sys_user` 表只有 `username`/`nickname`/`status`，没有敏感字段，所以**大概率不需要做任何事**——但这个检查必须显式做一遍并在 PR 描述里说明结论，不要默认没问题。

## 9. 实现顺序

1. `calculate` 工具 + 纯单测（表达式求值、round、非法表达式、变量缺失）
2. `AnalyticsToolProvider` 把六个工具收口
3. `AgentLoopExecutorFactory.forAnalytics` + **第 6.3 节的白名单断言测试**（先写这个测试，它会逼着你把工具列表写对）
4. `SKILL.md` + 验证 `SkillManager` 能加载到（写个测试断言 `SkillsTool` 的 description 里出现 `data-analysis`）
5. `AgentChatRequest` 加字段 + Controller 分支 + 回归验证现有 `mode=null` 路径没坏
6. 未启用时的 4xx 处理
7. 端到端冒烟测试（第 2 节最后两条）

## 10. 明确禁止事项

- **不要**给 DataAgent 执行器挂 Bash/FileSystem/Grep 任何一个工具
- **不要**只靠 SKILL.md 的提示词来禁止 Bash——运行时不挂载才是真正的隔离
- **不要**用 `ScriptEngine`/JS/Groovy 实现 `calculate`（能执行任意代码）
- **不要**把六个分析工具做成常驻工具
- **不要**改现有 `forModel`/`forModelWithCharts`/`forInternalOrchestration` 三个入口的行为
- **不要**碰 `RunnableParams`/`ToolParamInjector`/`ToolCallExecutor`
- **不要**用"根据用户消息内容判断是不是数据分析问题"的语义识别来路由——用显式 `mode` 字段
- **不要**沿用 `maxRounds = 10`
- 其余共享约束同 Ticket 6/7

## 11. 和现有代码的边界

**修改**：`web/AgentLoopExecutorFactory.java`（加一个入口 + 一个构造参数）、`web/AgentLoopExecutorConfig.java`（注册新 Bean）、`web/AgentLoopController.java`（一个三元分支）、`web/AgentChatRequest.java`（加一个字段）。
**新增**：`capability/analytics/AnalyticsToolProvider.java`、`capability/analytics/tools/CalculateTool.java`、`skills/data-analysis/SKILL.md`。
**不碰**：`loop/core/*`、`loop/model/RunnableParams.java`、前面五张票已交付的任何类的内部逻辑。
