# Domain Docs

工程任务开始前按需读取以下当前文档：

- `CONTEXT.md`：领域词汇和边界；
- `docs/requirements.md`：产品范围和验收口径；
- `docs/architecture.md`：当前分层与主链路；
- `docs/roadmap.md`：仍在计划中的工作；
- `docs/engineering-pitfalls-and-highlights.md`：已经验证的工程案例；
- `docs/adr/`：与改动相关的架构决策。

仓库是单 Maven 模块，不使用 `CONTEXT-MAP.md`。

## 写入规则

1. 使用 `CONTEXT.md` 中的 Runtime、Capability Pack、Tool、Skill、Hook 等术语。
2. 如果方案与 ADR 冲突，显式指出冲突并新增 ADR，不静默覆盖旧决策。
3. 当前状态必须回到代码、测试或配置验证，不能从旧审计和旧票据复制。
4. 公开文档不记录私人素材、本机路径、私有准备话术、内部来源或未脱敏凭据。
5. 调研、失败快照和施工过程材料写入本机 `.local-docs/`，不要强制加入 Git。
