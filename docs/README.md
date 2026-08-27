# 文档索引

这里仅保留理解、运行和评审 AgentTrail 所需的当前文档。历史审计、施工票据和内部材料不作为当前事实来源。

## 当前文档

| 文档 | 作用 | 更新条件 |
|---|---|---|
| [需求与边界](requirements.md) | 产品目标、现有能力、非功能要求和明确边界 | 范围或验收口径变化 |
| [架构说明](architecture.md) | 当前分层、主要链路、状态与存储模型 | 代码分层或主链路变化 |
| [配置说明](configuration.md) | 本地配置、密钥和部署覆盖方式 | 配置键或部署方式变化 |
| [路线图](roadmap.md) | 已完成基线和候选后续工作 | 阶段完成或优先级变化 |
| [工程案例](engineering-pitfalls-and-highlights.md) | 已验证的问题、修复与防回归证据 | 出现可复用工程结论 |
| [领域术语](../CONTEXT.md) | Runtime、Capability Pack、Tool、Skill、Hook 等统一词汇 | 引入或调整领域概念 |
| [ADR](adr/) | 已作出的架构决策及其理由 | 出现新的不可逆决策 |

## 设计摘要

- [Runtime 与工具执行](design/runtime.md)
- [数据分析与权限](design/analytics.md)
- [PPT 与深度研究工作流](design/workflows.md)
- [安全、可观测性与评测](design/security-observability.md)

## 工程协作配置

- [Issue tracker](agents/issue-tracker.md)
- [Triage 标签](agents/triage-labels.md)
- [领域文档消费规则](agents/domain.md)

## 写作规则

1. “当前状态”必须能由代码、测试、配置或已关闭的交付记录复核。
2. 日期快照不得冒充活文档；一次性审计和失败报告放入本地 `.local-docs/`。
3. 已解决问题写成“问题—修复—回归证据”，不继续使用“待实现”描述。
4. ADR 只增不改；决策被推翻时新增 ADR 并标记替代关系。
5. 不记录私人素材、本机绝对路径、真实密钥或来源导向的叙述。
