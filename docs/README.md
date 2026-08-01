# 文档索引

这份索引是唯一入口——新建文档前先看这里有没有已经该放的地方，避免"想到什么就开一个新文件"。

| 文档 | 角色 | 何时更新 | 状态 |
|---|---|---|---|
| [`../CONTEXT.md`](../CONTEXT.md) | 领域术语表，只放术语和边界 | 引入新概念时 | 活文档 |
| [`architecture.md`](architecture.md) | 架构图 + 代码目录结构 | 分层/包结构变化时 | 活文档 |
| [`roadmap.md`](roadmap.md) | **唯一权威的实施计划**：阶段划分、技术决策、依赖关系、参考来源映射 | 阶段完成/范围调整/技术决策变化时 | 活文档 |
| [`engineering-pitfalls-and-highlights.md`](engineering-pitfalls-and-highlights.md) | 踩坑点/亮点清单，`roadmap.md` 里的 `#N` 都指向这里 | 实现中发现新坑位/新亮点时追加编号 | 活文档 |
| [`interview-narrative.md`](interview-narrative.md) | 面试叙事三条主线 + demo/工程落地对照表 | 有新的可讲素材时 | 活文档 |
| [`adr/`](adr) | 架构决策记录（谁推翻了谁、为什么） | 做出不可逆的架构选择时新增一份，不修改旧的 | 只增不改 |
| [`specs/`](specs) | 涉及范围较大的新功能 spec（问题/方案/用户故事/实现决策/测试决策/范围外），`to-spec` 技能同一套模板；发 GitHub issue 前的书面留痕，issue 化之后本文件继续作为可读版本维护 | 新 spec 或 spec 状态变化时 | 活文档 |
| [`validation-report.md`](validation-report.md) | 一次性内部审查快照 | 不再更新 | **已归档，仅供个人参考，不对外展示** |
| [`../AGENTS.md`](../AGENTS.md) | 工程技能配置入口（`/to-spec`/`/to-tickets`/`/triage` 等读取的三份配置指针） | 切换 issue tracker/triage 词汇/文档布局时 | 活文档 |
| [`agents/issue-tracker.md`](agents/issue-tracker.md) | Issue tracker 配置：GitHub，`gh` CLI 操作约定 | 切换 tracker 时 | 活文档 |
| [`agents/triage-labels.md`](agents/triage-labels.md) | 五个标准 triage 标签到本仓库实际标签的映射 | 标签体系变化时 | 活文档 |
| [`agents/domain.md`](agents/domain.md) | 工程技能消费本仓库领域文档的规则（含不暴露迁移融合的规则） | 领域文档布局变化时 | 活文档 |

## 文档写作规则

1. **先查这张表，缺口应该并进现有文档，而不是新开文件。** 只有出现全新的文档类型（比如 ADR、比如运维手册）才新建。
2. **`roadmap.md` 是唯一的计划来源。** 不要在别的地方（issue、注释、临时笔记）另起一份互相不同步的计划。
3. **ADR 只增不改**：决策被推翻时新增一份 ADR 说明"为什么推翻"，旧 ADR 打上 `superseded-by` 标记，不删除、不覆写。
4. **对外文档不点名具体来源仓库/模块。** `architecture.md`/`roadmap.md`/`engineering-pitfalls-and-highlights.md`/`interview-narrative.md`/ADR 都是可能被展示的材料——方法论可以讲("研读了真实框架源码")，但不点名具体的个人历史项目或模块名。`validation-report.md` 这类确实需要点名具体来源做审查的内部笔记，必须标注"仅供个人参考，不对外展示"，且不能被其他活文档引用。
