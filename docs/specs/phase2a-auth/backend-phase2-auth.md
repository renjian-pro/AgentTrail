# AgentTrail 后端 — 用户体系与权限模型 需求 Spec

> 状态：草案，按 `to-spec` 模板整理，尚未发布为 GitHub issue（`ready-for-agent` 标签）。
> 前提：`docs/roadmap.md` Phase 2（SQL 数据分析能力包）目前后端还没有任何代码——没有 `sys_user` 表，没有认证依赖，`RunnableParams.userId()` 在所有生产调用点都是硬编码占位符（`"anonymous"`/`"deepresearch"`/`"ppt-generation"`）。这份 spec 是 Phase 2 的前置子集：只做"有真实登录用户 + 能解析出这个用户的数据可见范围"，不做 SQL 分析工具本身。
> 关联文档：与 [`frontend-phase2-auth.md`](frontend-phase2-auth.md) 是同一个契约的前后端两侧——前端 spec 已经假设了这里要交付的接口形状（登录三接口、用户管理 CRUD、部门树只读、`sys_user`/`sys_role`/`sys_dept`），这份 spec 落地后字段细节回填给前端票。
> 披露规则：同其余 spec，方法论表述为"研读了真实生产形态权限模型设计后独立实现"，不点名具体来源仓库。

## Problem Statement

当前工程处于两个叠加的问题状态：

1. **没有认证，且越权是真实可利用的，不是理论风险。** 逐一核对代码后确认：`AgentLoopController` 对每个请求硬编码 `userId = "anonymous"`；`GET /agent/v1/conversations` 查询没有任何 `WHERE` 条件，返回全表所有会话；`fileId`/`taskId` 都是自增主键，`content`/`resume`/`download`/`stop` 这几个接口都没有做归属校验——任何知道或枚举出一个 ID 的调用方，都能读到/操作到不属于自己的会话、文件解析结果、PPT 任务。
2. **Phase 2 SQL 能力包的核心卖点（按角色/部门自动收窄可见数据）本身依赖真实用户身份。** 数据范围解析必须拿到一个真实 `userId` 去查角色和部门，脱离登录体系这件事在设计上就不成立——不是"锦上添花"，是硬前提。

好消息是运行时机制已经提前设计好了：`RunnableParams`（`loop/model/RunnableParams.java`）已经有 `userId` 字段和 `toolParams` 双通道设计，`ToolParamInjector`（`loop/core/ToolParamInjector.java`）已经实现了"工具执行前用服务端预设值强制覆盖模型传入的同名参数"这一层防御，并且已经接到 `ToolCallExecutor.executeOne` 里。这套机制目前完全没人用（所有调用点 `toolParams` 传的都是空 Map），这份 spec 要做的是第一次让它真正跑起来，而不是重新设计。

## Solution

一句话：接入轻量认证框架做登录和会话管理，建一套标准的用户/角色/部门模型解析出"这个用户能看到哪些部门的数据"，把当前散落各处的硬编码身份占位符换成真实用户，并把现有的六个越权点逐一补上归属校验。

不新增 Agent 调用链路机制——`userId` 走已有的 `RunnableParams.toolParams` 强制注入通道，这一步只是让这个通道第一次被真正使用。

## User Stories

### 登录与会话

1. 作为访问者，我想用用户名密码登录换取一个 token，以便后续请求带着它证明身份
2. 作为已登录用户，我想在请求头带 token 访问受保护接口，以便不用每次都传用户名密码
3. 作为调用方，我想在没带 token 或 token 失效时收到统一的 401，以便前端能识别出"需要重新登录"
4. 作为已登录用户，我想主动登出，让当前 token 立即失效

### 数据归属与越权防护

5. 作为已登录用户，我只能看到自己创建的会话列表和历史，看不到别人的
6. 作为已登录用户，访问不属于自己的 `fileId`/`taskId`（文件内容、PPT 续跑、PPT 下载、停止任务）时，我想收到 403/404 而不是拿到对方的数据
7. 作为系统维护者，我想确认 Agent 工具调用链路里的 `userId` 一定来自服务端冻结的值，不能被模型的输出影响或伪造

### 用户 / 角色 / 部门管理

8. 作为管理员，我想对用户做增删改查、启用/禁用，并为其分配角色（可多个）和部门（可多个，支持跨部门兼职）
9. 作为管理员，我想在删除/禁用最后一个 `admin` 角色用户时被系统拒绝，避免系统失去管理入口
10. 作为管理员，我想查看角色列表和每个角色对应的数据范围（`ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF`），角色本身这次不开放动态增删（预置种子数据）
11. 作为管理员，我想查看部门树，用于给用户分配部门；部门树结构本身这次不开放增删改（预置种子数据）

### 为 Phase 2 SQL 铺路（这次只做到能解析范围，不做 SQL 改写）

12. 作为后续的数据权限改写逻辑，我想能传一个 `userId` 进来，拿到这个用户当前能看到的部门 ID 列表，用于后续拼接 SQL 过滤条件——这个能力这次要交付，但"怎么把它接进 SQL WHERE 子句"留给 Phase 2 SQL 票

## Implementation Decisions

### 认证技术选型

采用轻量级 Java 认证框架（基于用户名密码登录、Token 会话、Redis 存储），不用 Spring Security 的 Filter Chain/AuthenticationManager 全套模型——理由：项目已有 Redis 基础设施（Phase 1 分布式锁），复用同一套存储；概念面更小，接入成本低；Token 走请求头而不是 Cookie，规避 SPA 场景下的 CORS/CSRF 复杂度。

全局路由拦截器采用"默认拒绝、白名单放行"模型：除登录接口本身和 `OPTIONS` 预检请求外，全站接口默认要求登录态；Controller 层再加显式注解声明作为第二道保险（同时让接口意图自解释）。

### 数据模型：标准五表 RBAC + 部门树数据范围

```sql
-- 部门表（树形，ancestors 存祖先路径，查子树用前缀 LIKE，不用递归）
CREATE TABLE sys_dept (
    id          BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    parent_id   BIGINT       NOT NULL DEFAULT 0,
    ancestors   VARCHAR(500) NOT NULL DEFAULT '0',
    sort        INT          NOT NULL DEFAULT 0,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    INDEX idx_sys_dept_parent_id (parent_id)
);

-- 角色表：data_scope 挂在角色上（用户可多角色，取所有角色里范围最宽的）
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL,
    code        VARCHAR(64) NOT NULL,
    name        VARCHAR(64) NOT NULL,
    data_scope  VARCHAR(32) NOT NULL DEFAULT 'DEPT',  -- ALL/DEPT_AND_SUB/DEPT/SELF
    sort        INT         NOT NULL DEFAULT 0,
    status      VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
);

-- 用户表：不挂 dept_id，部门走多对多关联表
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL,
    username    VARCHAR(64)  NOT NULL,
    password    VARCHAR(128) NOT NULL,   -- BCrypt 哈希，不存明文
    nickname    VARCHAR(64),
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
);

CREATE TABLE sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    INDEX idx_sys_user_role_role_id (role_id)
);

-- 多对多：一个用户可挂多个部门，DEPT/DEPT_AND_SUB 的范围解析走这张表的并集
CREATE TABLE sys_user_dept (
    user_id BIGINT NOT NULL,
    dept_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, dept_id),
    INDEX idx_sys_user_dept_dept_id (dept_id)
);
```

密码用 BCrypt 哈希存储——这是相对已核实过的参考实现（明文存储，且明确标注为已知的开发期简化）的必要改进，不能沿用明文比较。

### 现有表改造（不改列类型，最小改动）

- `agent_session.user_id`、`agent_memory.user_id`：已有 `VARCHAR(100)` 列，不用改结构，改成存真实 `sys_user.id`（转字符串）而不是 `"anonymous"` 常量。
- `agent_file`：目前完全没有 `user_id` 列，需要新增（`ALTER TABLE agent_file ADD COLUMN user_id VARCHAR(100)`），否则 `content(fileId)` 接口无法做归属校验。
- `ppt_generation_task`：同样没有 `user_id` 列，需要新增，否则 `resume(taskId)`/`download(taskId)` 无法做归属校验。

项目目前没有迁移工具（Flyway/Liquibase），`schema.sql` 走手动 `DROP/CREATE`；开发库数据可直接清空重建，不需要写数据迁移脚本。

### 越权点逐一修复清单（对照现有代码走查结果）

| 位置 | 现状 | 要改成 |
|---|---|---|
| `AgentLoopController`（chat 主入口） | `RunnableParams` 硬编码 `userId = "anonymous"` | 取当前登录用户 |
| `AgentLoopController.stop(conversationId)` | 任何人传 `conversationId` 都能停止对应任务 | 停止前校验该会话属于当前登录用户 |
| `ConversationHistoryService.findPage` / `findConversations` | SQL 无 `user_id` 过滤条件 | 加 `WHERE user_id = ?` |
| `FileUploadController.content(fileId)` | `fileStore.findById(fileId)` 无归属校验 | 校验 `agent_file.user_id` 匹配当前登录用户 |
| `PptGenerationController.resume/download(taskId)` | 无归属校验 | 校验 `ppt_generation_task.user_id` 匹配当前登录用户 |
| `CapabilityConversationService.USER_ID` | 硬编码常量 `"anonymous"`，PPT/DeepResearch 结果落库统一用这个值 | 改成从调用方传入真实发起用户的 `userId`（子任务内部使用的 `"deepresearch"`/`"ppt-generation"` 这类内部标记用于区分调用来源，但最终写入 `agent_session` 的记录必须归属到发起请求的真实用户，两者不能混用） |

### userId 在 Agent 调用链路中的传递（复用已有机制）

不新增机制，只是让已经设计好但从未使用的通道第一次真正接线：

1. Controller 在 HTTP 请求线程里拿到当前登录用户 `userId`（认证框架的会话上下文绑定在原始请求线程上，一旦工具执行切到 Reactor 的独立调度器线程就拿不到，这是必须在最外层就把 `userId` "冻结"下来的原因）。
2. 构造 `RunnableParams` 时把 `userId` 同时填进 `userId` 字段和 `toolParams`（`Map.of("userId", userIdStr)`），跟着流式生命周期传递到工具执行阶段。
3. 工具执行前，已有的 `ToolParamInjector` 按目标工具 `inputSchema` 白名单，把 `toolParams` 里的 `userId` 强制覆盖模型自己填的任何值——不管模型抄了占位符、编了假值还是漏填。

这一条链路对 Phase 2 SQL 工具（`listTables`/`executeSql` 等）同样适用，工具只需要在 `inputSchema` 里声明 `userId` 参数，不需要额外接线。

### DataScopeResolver：解析用户可见部门范围（这次只做到这一步）

按 `userId` 查角色（多角色取 `data_scope` 最宽的一个）→ 查该用户挂载的全部部门（`sys_user_dept` 多对多）→ 按 `data_scope` 展开每个部门（`DEPT` 只取自身，`DEPT_AND_SUB` 用 `ancestors LIKE '前缀,%'` 查子树）→ 多部门结果取并集。

Fail-closed：部门树未加载完成、或用户没有任何角色/部门数据时，直接拒绝而不是默认放行成"全部可见"。

这一步的产出是"给定 `userId`，返回可见 `deptId` 列表"，**不做 SQL AST 改写**——把这个列表拼进 SQL WHERE 条件是 Phase 2 SQL 能力包票的范围，不在这次交付里。

### 种子数据

预置至少 3 个不同角色/部门/数据范围的账号，覆盖 `ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF` 四档中至少 3 档，另加一个跨多个部门的用户（验证 `sys_user_dept` 多对多并集逻辑没有"只读到第一个部门"这类实现缺陷）。这批账号也是 `frontend-phase2-auth.md` user story #21（"切换账号看到范围收窄"）的前提，两边要用同一批账号。

## Testing Decisions

- 越权测试：账号 A 登录后请求账号 B 的 `conversationId`/`fileId`/`taskId`，应得到 403/404，不能拿到内容
- 未登录访问受保护接口应统一返回 401
- `DataScopeResolver` 的多部门并集测试：跨部门用户必须能看到所有挂载部门的并集，不能只读到第一条关联记录
- `DataScopeResolver` 的 fail-closed 测试：角色/部门数据缺失时必须拒绝，不能默认放行
- 密码必须走 BCrypt 校验，不允许明文比较
- 集成测试起真实 MySQL/Redis（Testcontainers），不用 H2 替代——权限相关的 SQL 行为必须在真实数据库上验证

## Out of Scope

- 角色的新增/编辑/删除接口：预置种子数据，不开放动态角色管理（呼应前端 spec 的同一条限制）
- 部门树结构本身的增删改：这次只支持"选择已有部门"挂在用户身上
- 自助注册 / 找回密码：管理员预置账号，不需要自助流程
- 按钮级细粒度权限（谁能点哪个具体按钮）
- SQL 安全校验、M-Schema、`executeSql` 等工具本身的实现：`DataScopeResolver` 是这些工具未来的消费方，工具本身留给 Phase 2 SQL 能力包票
- 敏感字段脱敏：同样是 Phase 2 SQL 票的范围，不在用户体系这次交付内

## Further Notes

- 这次做完后，Phase 2 SQL 能力包票只需要实现 SQL 相关工具本身 + 调用已经就绪的 `DataScopeResolver` 拼接 WHERE 条件，不需要再回头补权限模型基础设施。
- 当前工作区里 `AgentLoopController`/`AgentLoopExecutorFactory`/`RunnableParams` 相关文件正在为 issue #38（V1 对话接口 SSE 化）做本地改动，这份票落地时要和那批改动协调一下，避免在同一批文件上产生冲突。
- `frontend-phase2-auth.md` 已经按"这些接口最终会长成什么样"的合理假设设计了页面，这份 spec 定稿后要把具体接口路径/请求响应字段回填给前端票。
