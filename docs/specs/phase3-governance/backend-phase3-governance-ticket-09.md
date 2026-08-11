# Ticket 9／9：安全纵深（Prompt Injection + 工具调用限速 + PII 检测 + 凭据隔离审查） — 技术开发文档

> 派生自 [`backend-phase3-governance.md`](backend-phase3-governance.md)。`Blocked by`
> [Ticket 1](backend-phase3-governance-ticket-01.md)——限速需要挂在 `PreToolUseHook` 上。

## 0. 范围边界

**这一票只做**：用户输入的 Prompt Injection 检测、单会话工具调用限速、用户输入 PII 打码、
Bash 工具执行环境的凭据隔离审查。四块彼此独立，可以任选顺序实现，不要求互相依赖。

## 1. Prompt Injection 检测

新增 `PromptInjectionGuard`（`loop.security` 新包），用一次小模型调用做意图分类——照抄
`MemoryExtractor`（`loop/memory/MemoryExtractor.java` 第 75-78 行）已经验证过的"单次同步
`chatModel.call(prompt)`"模式，不新发明一套调用方式：

```java
package com.agenttrail.loop.security;

public class PromptInjectionGuard {
    private final ChatModel chatModel;   // 用一个便宜的小模型，不需要和主对话同一个模型

    public boolean looksLikeInjection(String userInput) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(CLASSIFIER_PROMPT), new UserMessage(userInput)));
        String result = chatModel.call(prompt).getResult().getOutput().getText();
        return "true".equalsIgnoreCase(result.trim());
    }
}
```

`CLASSIFIER_PROMPT` 需要给出"忽略以上指令"类攻击的具体样例（few-shot），**这一票要实际收集
一批已知的 Prompt Injection 攻击样本用于测试**（网上有公开的攻击样本集合，不需要自己编，
但要注意别把攻击样本本身当作可以随意复制粘贴到代码仓库的第三方内容——用于测试断言即可，
不需要大段引用）。

接入点：`AgentLoopExecutor.stream()` 里构造 `UserMessage` 之前（原第 375 行附近），检测到
疑似注入时**这一票先按"拒绝这一轮请求，返回明确错误"处理**，不做更复杂的"隔离后继续执行"——
后者需要设计一套"降级处理"语义，超出这次范围。

**已知的假阳性/假阴性权衡**：小模型分类器不是 100% 准确，这一票不追求"零误判"，验收标准是
"能拦住第 4.1 节收集的已知攻击样本"，不是"永不误判正常请求"——如果实现时发现误判率高到
影响正常使用，把这条记进 Further Notes 作为后续要调优 prompt 的已知问题，不是这一票要解决
到完美的范围。

## 2. 工具调用速率限制

新增 `ToolRateLimiter`（`loop.security` 包），Redis 滑动窗口实现，复用 Phase 1 已有的
`RedissonClient`（`RedisConfig` 已经提供这个 Bean，条件挂载——**先验证**：没配 Redis 的开发
环境下这个限速机制要怎么优雅降级，参照 `AgentLoopExecutorConfig.agentTaskManager` 的写法
——`ObjectProvider<RedissonClient>.getIfAvailable()` 拿不到时直接放行不限速，不能让"没配
Redis"变成"应用启动失败"）：

```java
package com.agenttrail.loop.security;

public class ToolRateLimiter {
    private final RedissonClient redisson;   // null 表示不启用，永远放行
    private final int maxCallsPerWindow;
    private final Duration window;

    public boolean allow(String conversationId) {
        if (redisson == null) {
            return true;
        }
        // RScoredSortedSet 或 RRateLimiter（Redisson 自带的限流原语，优先用现成的，
        // 不要自己手写滑动窗口算法——Redisson 的 RRateLimiter 已经是生产验证过的实现）
        RRateLimiter limiter = redisson.getRateLimiter("tool-rate:" + conversationId);
        limiter.trySetRate(RateType.OVERALL, maxCallsPerWindow, window);
        return limiter.tryAcquire();
    }
}
```

接入点：挂成 Ticket 1 的 `PreToolUseHook` 的一个实现——**但要注意 Ticket 1 明确了这个 Hook 是
纯观察型（void），不能直接用它做拦截决策**。这里的正确做法是：`PreToolUseHook` 实现里检测到
超限时，不是"返回 false 拒绝"（接口没有返回值），而是**主动抛一个专门的运行时异常**
（比如 `ToolRateLimitExceededException`），让 `finishRound`/`executePendingToolCalls` 里
调用 `firePreToolUse(...)` 的地方捕获这个异常并转成一个"限速"错误结果喂回模型（参照
`ToolCallExecutor.executeOne` 里已有的"捕获异常转成 ToolResponse 错误"这套处理方式，不要
让异常真的冒出去中断整个循环）。**这一点是 Ticket 1 的 Hook 契约在这里被拉伸使用**——如果
实现时发现"用异常做流程控制"这个方式别扭，回头考虑给 `PreToolUseHook` 增加一个专门的
"限速类 Hook"接口（返回布尔值），不要勉强套用观察型 Hook 的形状，这里先给出一个可行方案，
不是唯一正确答案。

命中 `HIGH_RISK` 工具（Ticket 1 的 `ToolRiskRegistry`）在限速触发前，应该已经走 Ticket 2 的
人工审批流程——限速主要防的是 `READ_ONLY` 工具被 ReAct 死循环疯狂调用烧 token/请求量，两个
机制的作用对象有重叠但目的不同，不冲突。

## 3. PII 检测与打码

用户输入进 LLM 前做检测打码，参考 `houbb/sensitive`（公开开源脱敏库）的规则集设计思路——
**先验证**：这个库是否已经在 `pom.xml` 里（大概率没有，是新依赖），以及它的规则集是否覆盖
中文场景常见的手机号/身份证号/银行卡号格式（这是选它的前提，如果验证发现规则集不适配，
考虑只借鉴它的正则规则表自己实现，不引入整个依赖）。

接入点和 Prompt Injection 同一处（`AgentLoopExecutor.stream()`，构造 `UserMessage` 之前）——
打码后的文本才进入 `messages`，原始文本不落库、不进 prompt。**这一票不追求商用 DLP 产品的
准确率**，覆盖手机号/身份证号/银行卡号这几种最常见的格式即可。

## 4. Bash 工具执行环境的凭据隔离审查

这一节**主要是审查清单，不是大量新代码**：

- 走查 `BashTool`/`PathSandbox`/`ShellSessionManager`（`loop.tools` 包）的实现，确认 Bash 子
  进程的环境变量继承范围——**先验证**：`ProcessBuilder` 创建子进程时是不是默认继承了当前 JVM
  进程的全部环境变量（Java 默认行为就是继承），如果是，数据库密码/模型 API Key 这些配置在
  `application-local.yml` 里如果是通过环境变量注入的，Bash 工具执行的任意命令就能读到它们
  （比如 `env` 命令直接打印出来）——这是需要实际验证、不能假设"应该没问题"的真实风险点
- 如果验证确认存在这个问题，修复方式是 `ProcessBuilder.environment()` 显式清空/只保留白名单
  环境变量再启动子进程，不能依赖"希望模型不会想到调 `env`"
- 同样检查文件系统沙箱（`PathSandbox`）有没有可能被相对路径/符号链接绕过——这不是这一票要
  重新设计沙箱，是确认已有实现在"凭据隔离"这个新提出的视角下有没有漏洞，发现问题记录下来，
  是否在这一票里顺手修复取决于问题严重程度（真实可利用的漏洞应该修，理论上的边界情况可以
  记录成 Further Notes 留到后续）

## 5. Testing Decisions

- Prompt Injection：用第 1 节收集的已知攻击样本跑一遍，验证全部被拦截；用一批正常问题
  （包含"忽略""指令"这类词但语义正常的句子，比如"帮我写一段忽略空值的 SQL"）验证不会
  被误判拦截——这条反向测试很重要，只测"能拦住攻击"不够，还要测"不会误伤正常输入"
- 限速测试：短时间内连续触发超过阈值次数的工具调用，验证后续调用被限速（返回限速错误结果，
  不是让整个循环崩溃）；不同 `conversationId` 之间限速互不影响
- PII 测试：包含手机号/身份证号的输入，验证进入 `messages` 的文本已打码，原始文本不出现在
  任何落库记录里（含 Ticket 4 的 `agent_trace.input_data`）
- 凭据隔离测试：走一次 Bash 工具执行 `env`/`printenv` 类命令，验证输出里不包含数据库密码/
  API Key 这些敏感环境变量（如果第 4 节验证发现现状确实有问题，这个测试应该先复现问题
  再验证修复）

## Out of Scope

- Prompt Injection 检测器的准确率调优到生产级别
- PII 检测覆盖手机号/身份证号/银行卡号之外的其它类型
- 文件系统沙箱的全面安全审计（这一票只看凭据隔离这一个视角）
