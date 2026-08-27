# AGENTS

## Local-only documentation and publication constraint (global)

- All research notes, wiki imports, interview materials, architecture documents, and related artifacts must remain local to this workspace by default.
- Do not publish, upload, push, open a public PR/issue, or sync these materials to any external service unless the user explicitly authorizes that specific action.
- Do not call GitHub/remote publishing workflows as a normal completion step. If external publication is requested later, confirm the exact files and destination first.
- Treat credentials, API keys, database passwords, tokens, personal data, and copied private-source content as local-sensitive material. Never include them in public artifacts; recommend rotation/removal when discovered.
- Local edits to documentation are allowed when requested, but the default handoff is a local file path only.

## 机制设计必须有项目内证据

实现 Runtime 或业务能力前，先核对本项目的 ADR、规格、失败案例和测试基线，确认边界与验收标准后再编码。
设计不能只凭直觉，也不能把外部示例的结构、命名或叙述直接带进项目。

**证据顺序：**

1. 先读 `CONTEXT.md`、相关 ADR、spec 和 `docs/engineering-pitfalls-and-highlights.md`。
2. 用失败测试或最小 PoC 验证关键假设，记录版本、输入、输出和限制条件。
3. 数据库结构从当前领域模型、查询模式、索引验证和迁移兼容性推导，不复制样例库命名。
4. 采用公开标准或官方文档时，只记录必要的标准名称、版本和决策理由。
5. 代码、注释、提交信息和项目文档只描述 AgentTrail 自身的设计与证据，不出现私人素材库、本机路径或历史项目名。

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
