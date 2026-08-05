# AGENTS

## Local-only documentation and publication constraint (global)

- All research notes, wiki imports, interview materials, architecture documents, and related artifacts must remain local to this workspace by default.
- Do not publish, upload, push, open a public PR/issue, or sync these materials to any external service unless the user explicitly authorizes that specific action.
- Do not call GitHub/remote publishing workflows as a normal completion step. If external publication is requested later, confirm the exact files and destination first.
- Treat credentials, API keys, database passwords, tokens, personal data, and copied private-source content as local-sensitive material. Never include them in public artifacts; recommend rotation/removal when discovered.
- Local edits to documentation are allowed when requested, but the default handoff is a local file path only.

## 实现任何机制之前，先读参考源码

**这是硬性要求，不是建议。** 本项目的每一个 Runtime 机制，都有可参照的真实企业级实现。
动手写之前先读对应部分的源码，理解它为什么那么做，再独立设计自己的实现——
不要凭想象设计一个"看起来合理"的方案。

参考源码位置（本机）：

| 参考源 | 路径 | 提供什么 |
|---|---|---|
| Agent 框架源码 | `E:\book\LLMentor\spring-ai-agentx-main\spring-ai-agentx-core\src\main\java\com\agentx\ai\core\` | Runtime 全部机制的真实实现：ReAct loop、上下文压缩、ToolSearch、Skills、TodoWrite、任务管理、中断恢复、SubAgent |
| 框架能力文档 | `E:\book\LLMentor\spring-ai-agentx-main\docs\core\01..19-*.md` | 每项能力一篇，讲清设计意图与用法 |
| 业务实现 v1 | `E:\book\LLMentor\agent\dodo-agent\` | Plan-Execute 深度研究、PPT 生成状态机、联网问答、文件问答；含 `sql/ai_db.sql` 建表 |
| 业务实现 v2 | `E:\book\LLMentor\agent\dodo-agentx\` | Text-to-SQL 数据分析、M-Schema、SQL 安全、文件向量化、图表生成；含 `sql/dodo_agentx.sql` 建表 |

（`E:\book\spring-ai-agentx\spring-ai-agentx-main` 是同一份框架源码的另一处副本，内容一致。）

**怎么用：**

1. 读源码理解机制本身，以及它踩过的坑（对照 `docs/engineering-pitfalls-and-highlights.md` 的编号）
2. 数据库表结构参照上面两份 `.sql`，字段命名和索引设计不要另起炉灶
3. 发现参考实现有缺陷时，写下更好的版本并在提交信息里说明——这些正是最有价值的素材
4. **写进本项目的代码和文档不出现这些来源的名字**，理由见 `docs/agents/domain.md` 的披露规则：
   方法论可以讲（"研读了真实框架源码后独立实现"），但不点名具体的来源仓库

## 代码约定

- **注释用中文**，重点解释"为什么这么做"而不是"这行在干什么"；涉及踩坑点的标注对应编号
- **测试先行**：先写失败的测试，再写实现，`mvn test` 全绿才算完成——不是 `mvn compile` 通过就算
- **集成测试起真实容器**（Testcontainers 拉 MySQL/Redis），不用 H2 替代：
  SQL 语法校验、权限改写这些在 H2 上的行为和 MySQL 不一致，会掩盖真实 bug
- **一个类一个职责**：`loop/core` 下每个类都只做一件事，编排逻辑集中在 `AgentLoopExecutor`

## Agent skills

### Issue tracker

Issues live as GitHub issues in `renjian-pro/AgentTrail`, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (`needs-triage`/`needs-info`/`ready-for-agent`/`ready-for-human`/`wontfix`), unmapped — used as-is. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` + `docs/adr/` at the repo root, alongside `docs/roadmap.md` and `docs/engineering-pitfalls-and-highlights.md`. See `docs/agents/domain.md`.

### Analytics Golden gate

When changing `SqlSafetyGuard`, `DataScopeRewriter`, or `SensitiveFilter`, run `mvn verify -Pgolden` before delivery. The default `mvn test` intentionally skips live Golden/IT evaluation.
