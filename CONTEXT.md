# CONTEXT

本项目的领域术语表。只放术语和边界，不放实现细节。

## Runtime（载体 / 通用任务 Agent 运行时）

与具体业务场景无关的执行底座：Agent Loop、Tool Calling、记忆、状态持久化、Skill 加载、Hooks。AgentTrail 的主体就是 Runtime 本身。

## Capability Pack（能力包）

跑在 Runtime 上的一组可安装能力 = 一份 Skill 定义 + 它需要的若干 Tool。例：研究报告、PPT 生成、工作提效任务。能力包的增加不应要求修改 Runtime 代码。

## Tool（工具）

Runtime 可调用的单个原子动作，带名称、描述、参数契约。模型只生成调用意图，执行永远在 Runtime 侧。

## Skill（技能）

告诉 Agent"什么时候、按什么套路做某类任务"的任务说明书，按需加载（先元数据、命中后再读正文）。Skill 决定"怎么做"，Tool 提供"能做什么"。

## Hook（钩子）

Runtime 生命周期上的拦截点（如工具执行前后、会话开始结束、预算检查），承载权限门禁、审计、熔断、降级。治理逻辑只能放在 Hook/代码层，不依赖模型自觉。

## LlmClient（模型接入口）

Runtime 与模型之间的唯一边界接口。当前实现直连 DeepSeek；将来替换为指向独立网关服务的实现，调用方不感知。

## Gateway（网关）

未来独立部署的模型调用治理服务（路由、Fallback、预算、审计）。不属于 Runtime 的一部分；Runtime 通过 LlmClient 消费它。
