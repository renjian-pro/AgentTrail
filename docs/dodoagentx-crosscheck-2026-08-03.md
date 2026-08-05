# Dodo-agentx / Clippings 与 AgentTrail 交叉核对（2026-08-03）

## 结论

Clippings 和 `E:\book\LLMentor\agent\dodo-agentx` 对 AgentTrail 最有价值的部分是数据分析领域的工程约束，而不是某个 Agent Runtime 的代码可以直接搬过来。dodo-agentx 实际使用的是自研/定制的 `spring-ai-agentx-core` `ReactAgent`，并不是 AgentScope Java；因此应借鉴业务边界、工具安全和状态设计，不把它当作 AgentScope 的落地证明。

推荐路线：DataAgent 采用 AgentScope 作为隔离的 Runtime Adapter（ReAct/Skill），外面仍由 AgentTrail 的 Task、Checkpoint、Artifact、权限和审计层负责；普通 Chat、DeepResearch、PPT 暂不改成 AgentScope。SQL 安全、数据权限、脱敏、Schema 语义和结果校验必须由业务端独立实现，不能由 AgentScope 或 Skill 提示词代替。

## 已经覆盖与需要补充

| 领域 | AgentTrail 已有 | 交叉核对后的补充/修正 |
|---|---|---|
| ReAct Runtime | `AgentLoopExecutor`、工具调用、流式事件、上下文压缩、暂停恢复 | DataAgent 不应被强行写成固定 SQL DAG；采用“AgentScope ReAct + DataAgent Skill + 外层 Task/Checkpoint”混合形态 |
| Schema | 规划了 SQL 能力包 | 增加 `SchemaProvider` 策略、M-Schema 动态元数据、YAML 业务语义目录、版本/校验/漂移检测；推荐动态元数据 + 静态业务目录混合 |
| 业务消歧 | 有术语工具的规划 | 增加 glossary/口径工具和安全探针查询，避免模型猜字段、枚举值和日期口径 |
| SQL 安全 | 路线图有 SQL 安全方向 | `validateSql` 与 `executeSql` 双重校验；AST 递归检查 CTE/UNION/子查询/JOIN；只读账号、LIMIT/超时/行数上限、EXPLAIN 预检；失败必须 fail-closed |
| 数据权限 | 规划了 RBAC/数据范围 | 权限上下文必须来自认证主体，服务端重写 SQL；LEFT JOIN 的右表条件要进入 ON；改写失败拒绝执行，不能只把权限写进 Prompt |
| 敏感数据 | 规划脱敏 | 脱敏必须发生在结果返回模型和前端之前，且默认打开；不要只依赖字段名，结合 JDBC 元数据和目录配置 |
| 计算/图表 | 有图表能力规划 | 增加安全 `calculate` 工具；图表只保存对象存储 Artifact URL，不把 Base64 塞进上下文 |
| 工具装配 | Runtime 有 ToolGateway 方向 | DataAgent 实现常驻/条件/延迟工具分层；Bash、文件系统、Grep 等通用工具不能仅靠 SKILL.md 禁止，应在运行时工具权限中硬隔离 |
| 评测 | 评测留在后续阶段 | 增加 Golden Tasks：SQL 正确率、权限泄漏、敏感字段泄漏、空结果解释、重试次数、延迟/Token、结果可复现性 |

## 与现有路线图/重构方案的冲突

1. `roadmap.md` 当前把认证/数据权限绑定在 Phase 2，但 SQL 数据分析不能在固定 `anonymous` 身份下进入生产路径。应增加 Phase 0.5（或 Phase 2A 前置）身份、租户、资源所有权、审计和数据权限基础。
2. 重构蓝图把 DataAnalysis 泛化为 Workflow；dodo 的经验表明分析路径是开放式 ReAct，七个阶段是 SOP 而不是必须逐节点执行。Workflow 只包裹长任务、队列、checkpoint 和 Artifact，不替代 Agent 的决策循环。
3. Phase 3 治理不能完全晚于 Phase 2：SQL AST 安全、数据范围、脱敏、审计是 DataAgent 的上线前置条件，应提前为 DataAgent 的硬门禁。
4. Phase 10 的 AgentScope 评估仍然保留，但新增 Phase 2A AgentScope DataAgent Pilot；Phase 10 变为基于 Golden Tasks 的最终迁移决策，而不是突然全局替换。

## 登录材料核对结果

可复用的设计：Sa-Token + Redis 会话、服务端获取登录主体、默认拒绝的全局拦截、账户/用户档案分离、角色与部门数据范围、身份通过不可见运行参数传给工具。AgentTrail 当前仍有 `/agent/v1/**` 固定 `anonymous`，并且没有完整认证依赖/主体上下文，这必须在 SQL 能力包之前收敛。

需要修正的事实和安全问题：

- dodo-agentx 的登录实现和 Schema 仍是明文密码比较/存储，文档中“生产用 BCrypt”只是注释，不是实现；应使用 BCrypt/Argon2、统一登录失败提示并限流，避免账号枚举和暴力破解。
- 文档写的是 `/api/auth/*` 和字符串 `userId`，实际代码是 `/auth/*`、`Long userId`；路线图应统一 API 契约和 ID 类型。
- 文档描述了 CORS/OPTIONS 处理，但实际 `SaTokenConfig` 未看到对应 CORS Filter；部署前必须单独验证跨域、Cookie/Bearer、CSRF 和反向代理配置。
- `LoginUserVO` 不应默认返回身份证、住址等档案敏感字段；“当前用户信息”接口只返回展示所需字段。
- AgentTrail 不得继续使用生产默认 `anonymous`；所有会话、文件、任务、Artifact 读写都按 `tenantId + userId` 做所有权校验。
- Clippings、`application.yml` 和示例代码中存在凭据样式的 API Key、数据库/Redis 密码。无论是否仍有效，都应立即撤销/轮换，改用环境变量或密钥管理，并清理历史提交和导出文档。

## 推荐目标调用链

```text
HTTP/Auth -> Principal(tenantId,userId,roles) -> AnalysisTaskService
  -> AgentScopeDataAnalysisAdapter(ReAct + Skill + RuntimeContext)
  -> hard-scoped Tools
       Schema(M-Schema/YAML) -> glossary/probe
       -> validate SQL(AST) -> rewrite data scope -> read-only execute
       -> mask result -> calculate/chart Artifact -> result verifier
  -> Task/Checkpoint/Event/Artifact persistence -> SSE/query API
```

## 参考资料

- Clippings：`✅data-agent 整体流程`、`✅M-Schema的Java实现`、`✅Yaml Schema方式`、`✅SQL安全校验`、`✅执行SQL的完整流程`、`✅权限模型的改造`、`✅如何改写数据权限`、`✅敏感字段如何脱敏`、`✅登录体系开发及介绍`。
- AgentScope 2.0 官方文档：`https://java.agentscope.io/v2/en/docs/quickstart.html`、`https://java.agentscope.io/v2/en/docs/building-blocks/middleware.html`、`https://java.agentscope.io/v2/en/docs/building-blocks/permission-system.html`。

