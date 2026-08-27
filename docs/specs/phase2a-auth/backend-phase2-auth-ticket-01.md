# Ticket 1／4：用户/角色/部门数据模型 + 登录鉴权接入 — 技术开发文档

> 派生自 [`backend-phase2-auth.md`](backend-phase2-auth.md)。这份文档比该 spec 更细，是专门写给**实现者按步骤照做**用的（包括能力较弱的实现模型）——每一步给出精确文件路径、精确签名、精确 SQL，并且明确列出"不要做什么"。不确定的地方标了「先验证」，不允许跳过验证直接假设。
>
> Ticket 2/3/4 都 `Blocked by` 这一票，这一票不完成后面无法开始。

## 0. 范围边界（先划清楚，防止越界）

**这一票只做**：建表、种子数据、登录/登出/取当前用户三个接口、全局登录拦截器。

**这一票不做**（不要顺手实现，留给后面的票）：
- 用户管理 CRUD 接口（Ticket 2）
- `AgentLoopController` 等现有 Controller 里的越权修复、`RunnableParams.userId` 从硬编码 `"anonymous"` 换成真实值（Ticket 3）
- `DataScopeResolver`（Ticket 4）
- 任何 SQL 分析相关的工具

看到"顺手把 XX 也做了"的冲动时，先检查是不是在这四条里，不在就不做。

## 1. 验收标准（机械可核对，不满足不能算完成）

- [ ] `mvn test` 全绿，新增测试全部基于 Testcontainers 起真实 MySQL（不允许用 H2）
- [ ] `mvn spring-boot:run` 能正常启动，无依赖冲突/自动配置报错
- [ ] `schema.sql` 新增 5 张表，`CREATE TABLE IF NOT EXISTS` 语义，重复执行不报错
- [ ] 种子数据脚本执行后，数据库里存在 4 个测试账号，覆盖 `ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF` 四档中至少 3 档，另有 1 个跨部门账号
- [ ] `POST /api/auth/login` 用种子账号能登录成功，返回 token
- [ ] `POST /api/auth/login` 用错误密码返回统一的"用户名或密码错误"（不能分辨是账号不存在还是密码错，防枚举）
- [ ] `GET /api/auth/info` 带合法 token 返回当前用户信息，不带 token 返回 401
- [ ] `POST /api/auth/logout` 之后，原 token 再次请求受保护接口返回 401
- [ ] 除 `/api/auth/login` 和 `OPTIONS` 请求外，任意其它接口未登录访问一律 401（可以拿现有的 `/agent/v1/chat` 临时验证，不需要为这张票新增业务接口）
- [ ] 密码校验用 BCrypt，`sys_user.password` 列里的值不是明文（人工检查种子数据插入后的实际存储值）
- [ ] `SysStpInterface.getRoleList` 正确返回登录用户的角色代码列表；随便找一个临时加了 `@SaCheckRole("admin")` 的测试接口验证非 admin 账号被拒绝、admin 账号放行

## 2. 开工前必须验证的技术假设（不要跳过，不要凭记忆假设）

项目当前 `pom.xml` 的 Spring Boot parent 版本是 **4.1.0**（见 `pom.xml:8`）。市面上常见的 Sa-Token starter 是按 Boot 大版本分开发布的（`sa-token-spring-boot-starter` 对应 Boot 2，`sa-token-spring-boot3-starter` 对应 Boot 3）。**是否存在对应 Boot 4 的 starter、或者 Boot 3 版本的 starter 能否在 Boot 4.1.0 的父 POM 下正常解析和启动，这一点没有已验证的结论，必须在写业务代码前先确认**：

1. 先只加认证依赖（不写任何业务代码），跑一次 `mvn dependency:tree`，检查有没有版本冲突或者被排除的关键依赖。
2. 跑一次 `mvn spring-boot:run`（或 `mvn test` 里一个最小的 `@SpringBootTest` 上下文加载测试），确认应用能正常启动，没有 `ClassNotFoundException`/`NoSuchMethodError`/自动配置失败。
3. 如果验证失败：参考项目里 `docs/adr/0001-runtime-scope-and-stack.md` 处理另一个 Boot 4 兼容性问题（`agentscope-spring-boot-starter:2.0.0` 依赖 `spring-boot-autoconfigure:4.0.1`）时的方式——检查该依赖的 GitHub Releases/Maven Central 有没有更新的、明确支持 Boot 4 的版本；如果只有 Boot 3 版本，尝试显式声明兼容的传递依赖版本覆盖冲突项，而不是直接放弃这个技术选型。
4. 把验证结果（用的具体版本号、是否需要额外的版本覆盖）记录成一条 commit message 或者这份文档的追加说明，后面的票会依赖这个结论。

**在这一步验证通过之前，不要往下写任何依赖认证框架的业务代码。**

## 3. Maven 依赖改动

```xml
<!-- 具体 artifactId/version 以第 2 节验证结果为准，下面是起点，不是最终答案 -->
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-spring-boot3-starter</artifactId>
    <!-- 版本号：先查 Maven Central 上的最新稳定版，不要凭记忆写一个可能已过期的号 -->
</dependency>
<dependency>
    <groupId>cn.dev33</groupId>
    <artifactId>sa-token-redis-jackson</artifactId>
</dependency>
```

Redis 连接复用项目已有配置（Phase 1 分布式锁已经在用 Redis），不要新开一套 Redis 连接配置。

## 4. 建表 SQL（追加到 `src/main/resources/db/schema.sql`，风格必须对齐文件里现有的表）

对齐点（沿用现有表的风格，不要另起一套）：
- `CREATE TABLE IF NOT EXISTS`
- 每张表前面一段中文注释，解释"为什么这么设计"而不是复述字段名
- 表尾统一 `ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '...'`
- `created_at`/`updated_at` 用 `BIGINT` 存 epoch millis——这是刻意选择：`schema.sql` 里 `agent_trace`/`agent_memory`/`agent_pause_state`/`agent_file`/`ppt_generation_task` 这五张较新的表都是这个风格（注释里写明"和 Java 领域对象字段类型直接对应，不用做 java.time 转换"），只有最早的 `agent_session`/`agent_skill` 两张还是 `TIMESTAMP`。新表跟最新的约定走，不要跟最早的两张学。

```sql
-- 用户/角色/部门（issue #<填入实际 issue 号>）：标准 RBAC 五表，用户与部门是多对多
-- （sys_user_dept），支持跨部门兼职；角色上的 data_scope 决定这个角色能看多大范围的数据，
-- 用户可以多角色，取所有角色里最宽的范围——这个取舍在 DataScopeResolver（Ticket 4）里实现，
-- 这一票只建表占位。

CREATE TABLE IF NOT EXISTS sys_dept
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门 id，顶级部门为 0',
    -- 从根到父节点的完整路径，逗号分隔。查子树用 WHERE ancestors LIKE '前缀,%' 一次搞定，
    -- 不用递归 CTE——MySQL 8 虽然支持 WITH RECURSIVE，但前缀 LIKE 配合索引更简单也更快，
    -- 代价是部门挪动时要重写所有子节点的 ancestors，部门结构变动很少，值得
    ancestors  VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖先路径，逗号分隔 id 链',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '最近更新时刻（epoch millis）',
    PRIMARY KEY (id),
    KEY idx_sys_dept_parent (parent_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '部门表（树形，ancestors 缓存祖先路径）';

CREATE TABLE IF NOT EXISTS sys_role
(
    id         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(64) NOT NULL COMMENT '角色代码，如 admin/manager/analyst/employee',
    name       VARCHAR(64) NOT NULL COMMENT '角色名称',
    -- 挂在角色上而不是用户上：用户可以多角色，实际数据范围取所有角色里的最大值，
    -- 这个逻辑需要"角色→范围"是稳定映射才好算，挂用户上会让每个用户都要单独维护这个值
    data_scope VARCHAR(32) NOT NULL DEFAULT 'DEPT' COMMENT '数据范围：ALL/DEPT_AND_SUB/DEPT/SELF',
    sort       INT         NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status     VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at BIGINT      NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT      NOT NULL COMMENT '最近更新时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '角色表';

CREATE TABLE IF NOT EXISTS sys_user
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    -- BCrypt 哈希，不存明文。登录校验用 BCryptPasswordEncoder#matches，不要用 equals 比较
    password   VARCHAR(128) NOT NULL COMMENT '密码（BCrypt 哈希）',
    nickname   VARCHAR(64)  NULL COMMENT '昵称，用于列表展示',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED，禁用后无法登录',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '最近更新时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '用户表（不挂 dept_id，部门走 sys_user_dept 多对多）';

CREATE TABLE IF NOT EXISTS sys_user_role
(
    user_id    BIGINT NOT NULL COMMENT '用户 id',
    role_id    BIGINT NOT NULL COMMENT '角色 id',
    created_at BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '用户-角色关联表';

CREATE TABLE IF NOT EXISTS sys_user_dept
(
    user_id    BIGINT NOT NULL COMMENT '用户 id',
    dept_id    BIGINT NOT NULL COMMENT '部门 id',
    created_at BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    -- 多对多是关键设计，不是可选项：一个用户可以挂多个部门（跨部门兼职），
    -- 单值 dept_id 字段表达不了这件事，DataScopeResolver（Ticket 4）的并集逻辑依赖这张表
    PRIMARY KEY (user_id, dept_id),
    KEY idx_sys_user_dept_dept (dept_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '用户-部门关联表（多对多，支持跨部门兼职）';
```

## 5. 种子数据（追加到 schema.sql 同文件末尾，或单独一个 seed 区块，风格自定但要有注释说明用途）

至少要有这些账号（具体 id/密码明文自己定，密码入库前务必过 BCrypt）：

| username | 角色 code | data_scope | 部门 | 用途 |
|---|---|---|---|---|
| `admin` | admin | ALL | 集团顶层 | 验证全局可见 |
| `mgr_test` | manager | DEPT_AND_SUB | 某个二级部门 | 验证含子部门的范围 |
| `analyst_test` | analyst | DEPT | 某个末级部门 | 验证仅本部门 |
| `cross_analyst` | analyst | DEPT | **两个**末级部门（`sys_user_dept` 两条记录） | 专门验证多部门并集，Ticket 4 的测试会直接依赖这个账号 |

部门树不需要做到 27 个部门 4 级那么复杂，够验证 `DEPT` vs `DEPT_AND_SUB` 的区别即可（至少 3 级：顶级 → 二级 → 三级）。

## 6. 新增 Java 文件清单（精确路径 + 每个类的唯一职责）

包路径新建 `com.agenttrail.auth`（认证相关）和 `com.agenttrail.sys`（用户/角色/部门数据）两个包，和现有的 `com.agenttrail.loop.*`/`com.agenttrail.web.*` 平级，不要塞进 `loop` 包（`loop` 只装 Runtime 通用机制，不含业务身份这类领域概念，参照 `CONTEXT.md` 的边界定义）。

| 文件 | 职责（只做这一件事） |
|---|---|
| `com/agenttrail/sys/entity/SysUser.java` | 用户实体（record 或普通 POJO，和项目里其它领域对象风格一致，先看一眼 `loop/model` 下现有 record 用法） |
| `com/agenttrail/sys/entity/SysRole.java` | 角色实体，含 `dataScope` 字段 |
| `com/agenttrail/sys/entity/SysDept.java` | 部门实体，含 `ancestors` 字段 |
| `com/agenttrail/sys/JdbcUserStore.java` | 用户表的 JDBC 读写。**必须用 `org.springframework.jdbc.core.simple.JdbcClient`，不是 `JdbcTemplate`，不是 MyBatis，不是 JPA**——参照 `loop/persistence/JdbcSessionStore.java` 的写法：SQL 用文本块常量、构造函数接收 `DataSource` 自己 `JdbcClient.create(dataSource)`，类和方法都写 Javadoc |
| `com/agenttrail/sys/JdbcRoleStore.java` | 角色表读写，同上风格 |
| `com/agenttrail/sys/JdbcDeptStore.java` | 部门表读写 + `ancestors` 前缀查询方法，同上风格 |
| `com/agenttrail/auth/AuthService.java` | 接口：`login(username, password) -> token`、`logout()`、`getCurrentUserId() -> Optional<Long>` |
| `com/agenttrail/auth/AuthServiceImpl.java` | 实现，内部调用 `JdbcUserStore` 查用户 + `BCryptPasswordEncoder` 校验密码 + Sa-Token `StpUtil.login(userId)` |
| `com/agenttrail/auth/LoginController.java` | 三个接口：`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/auth/info`，都是薄封装，业务逻辑在 `AuthService` |
| `com/agenttrail/auth/SaTokenConfig.java` | `@Configuration`，注册全局拦截器：`SaRouter.match("/**").notMatch("/api/auth/login").check(r -> StpUtil.checkLogin())`；`OPTIONS` 请求直接放行（预检请求没有 token） |
| `com/agenttrail/auth/SysStpInterface.java` | 实现 Sa-Token 的 `StpInterface`，只实现 `getRoleList(loginId, loginType)`（按 `userId` 查 `sys_user_role` → `sys_role.code`，返回角色代码列表）——**这是必须的**，`@SaCheckRole`/`StpUtil.checkRoleOr` 这类角色校验在没有这个实现时不会正确工作，任何用到角色校验的后续票（Ticket 2 的管理后台门禁）都依赖这个类存在。`getPermissionList` 这次先返回空列表 `List.of()`，权限点粒度的校验是独立的后续票（见 `backend-phase2-auth-ticket-05.md`），这个方法到那张票再补实现，不要在这一票里提前设计权限点表结构 |

## 7. 实现顺序（照这个顺序做，不要跳步，每步先写失败的测试）

1. 第 2 节的依赖验证（不写代码，只验证能不能跑起来）
2. 建表 SQL + 种子数据，写一个纯 SQL 层面的测试（Testcontainers 起 MySQL，执行 schema.sql，查种子账号确实存在且密码不是明文）
3. `SysUser`/`SysRole`/`SysDept` 实体 + `JdbcUserStore`/`JdbcRoleStore`/`JdbcDeptStore`，每个 Store 先写测试（查用户、查角色、按部门查子树的前缀查询）再写实现
4. `AuthServiceImpl.login`：先写测试（正确密码登录成功返回 token、错误密码抛统一异常、禁用账号不能登录），再写实现
5. `LoginController` 三个接口：先写 `@SpringBootTest` 级别的集成测试（真的发 HTTP 请求验证登录/登出/取用户信息），再写 Controller
6. `SaTokenConfig` 全局拦截器：写测试验证"未登录访问任意受保护路径返回 401，`/api/auth/login` 本身不需要登录"，再写配置

## 8. 明确禁止事项（常见跑偏点，逐条对照）

- **不要**引入 Spring Security——这次的技术选型是轻量认证框架（Sa-Token），不是 Spring Security 的 Filter Chain/AuthenticationManager 那套
- **不要**用 MyBatis 或 JPA/Hibernate 做持久化——项目现有持久化全部是手写 `JdbcClient`，新代码必须跟这个风格，不要引入新的 ORM 依赖
- **不要**把密码明文存库或用 `.equals()` 比较——必须 `BCryptPasswordEncoder`
- **不要**给 `RunnableParams` 这个 record 新增字段或方法——这一票不涉及 Agent 调用链路改动（那是 Ticket 3 的范围），不要提前碰 `loop/model/RunnableParams.java`
- **不要**用递归 CTE 查部门子树——用 `ancestors` 字段的前缀 `LIKE` 查询（第 4 节的表注释已经说明原因）
- 代码注释、commit message 和项目文档只说明 AgentTrail 的 RBAC 约束与验证证据，不记录私人素材、本机路径或历史项目名（见 `AGENTS.md`）
- **不要**在集成测试里用 H2 替代 MySQL——`AGENTS.md` 明确要求 Testcontainers 起真实容器，H2 在 SQL 行为上和 MySQL 不一致会掩盖真实 bug
- **不要**做自助注册/找回密码接口——账号只能通过种子数据或后续 Ticket 2 的管理员后台创建
- **不要**做角色/部门的新增编辑删除接口——这次角色和部门都是种子数据，动态管理接口不在这几张票的范围内（`backend-phase2-auth.md` 的 Out of Scope 已经写明）

## 9. 和现有代码的边界

这一票**不修改**任何现有文件（`AgentLoopController`、`RunnableParams`、`ConversationHistoryService` 等一律不碰）。当前工作区里这些文件正在为 issue #38（V1 对话接口 SSE 化）做本地改动，这一票和它们没有交集，纯新增文件，不会产生冲突。真正去改这些现有文件、把硬编码的 `"anonymous"` 换成真实登录用户，是 Ticket 3 的范围。
