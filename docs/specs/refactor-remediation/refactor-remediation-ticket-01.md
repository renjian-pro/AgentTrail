# Ticket 01：Golden Case / 审计接口权限校验补齐 — 技术开发文档

> 派生自 [refactor-remediation.md](refactor-remediation.md)。

## 0. 范围边界

**这一票只做**：给 `GoldenCaseController.java`、`GoldenCandidateController.java`、
`TraceAuditController.java`（均在 `src/main/java/com/agenttrail/web/controller/`）补上权限校验，
抄现有 `sys` 模块的注解写法和权限码命名规则。不涉及 `GoldenEvaluationController`（评测触发/查询接口，
和这三个同样没有权限注解，但不在本票列出的三个类范围内，见 Out of Scope）。

## 1. 先验证：现有权限体系长什么样

读了 `sys.controller.*`（`SysUserController`/`SysRoleController`/`SysPermissionController`/
`SysDeptController`）和已经接线的 `loop.skills.SkillController`，确认两件事，纠正题面最初的假设：

1. **这个项目里没有任何地方用 `@SaCheckRole`**——全部走 `cn.dev33.satoken.annotation.SaCheckPermission`，
   细粒度权限码，不是粗粒度角色码。权限命名统一是 `module:resource:action`（`sys:user:view`/
   `sys:user:create`/`sys:user:manage-status`）或退化成 `module:action`（`skill:view`/
   `skill:manage-status`，技能没有"资源"这一级）。GET 列表接口也照样挂权限码（`sys:user:view`/
   `skill:view`），不是"只有写操作才管控"——`db/schema.sql` 342-353 行的 `sys_permission` 种子
   数据里能看到全部现有权限码，照这个粒度抄，不要发明 `@SaCheckRole("admin")` 这种本项目里
   不存在的写法。
2. **权限码要注册进 `sys_permission` 表，不是只在代码里声明字符串就生效**——`SaCheckPermission`
   靠 `StpInterceptor` 查用户角色关联的权限点集合来放行，权限码如果没有被任何角色关联，
   即使是 admin 也会被拒绝。好消息是 `schema.sql` 356-357 行已经有一条兜底语句：
   ```sql
   INSERT INTO sys_role_permission (role_id, permission_id, created_at)
   SELECT 1, id, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 FROM sys_permission
   ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);
   ```
   这条语句把 `sys_permission` 表里**当前存在的每一条**权限都自动关联给 role 1（`admin`）。
   `spring.sql.init.mode: always` 意味着这段 DDL/DML 每次启动都重跑一遍——新增权限码只需要
   在 `sys_permission` 的 `INSERT` 语句里加几行，**不需要**另外手写 `sys_role_permission` 的
   insert，这条兜底语句会自动把新权限码授给 admin。

## 2. 更严重的发现：`TraceAuditController` 现在完全没有鉴权，不只是"缺角色校验"

`refactor-blueprint.md` §4.1 把这个问题描述成"任何已登录用户可调用审计接口"，但实际读
`SaTokenConfig.java` 发现问题比这个描述更严重：

```java
@Bean
public SaInterceptor saInterceptor() {
    return new SaInterceptor().isAnnotation(true).setAuth(obj -> {
        SaRouter.match("/agent/**", "/api/**")
                .notMatch("/api/auth/login")
                .check(r -> { ... StpUtil.checkLogin(); ... });
    });
}

@Override
public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(saInterceptor()).addPathPatterns("/agent/**", "/api/**");
}
```

`TraceAuditController` 的路径是 `/internal/audit/{conversationId}/verify`——**不匹配
`/agent/**` 也不匹配 `/api/**`**，`addInterceptors` 只对这两个前缀注册了拦截器。这意味着这个
接口现在连 `StpUtil.checkLogin()` 都不会跑，是**完全公开、未登录也能调用**的状态，比
"登录用户越权"严重一个等级。而且因为 `isAnnotation(true)` 的注解处理也是这同一个拦截器负责的，
单独给方法加 `@SaCheckPermission` **不会生效**——请求根本不会经过这个拦截器。

**修复方式**：把路径挪到 `/api/**` 前缀下，和 `sys`/`skill` 的管理接口用同一套已经生效的拦截器
覆盖，不要去改 `SaTokenConfig` 的路由规则（改路由规则影响面更大，且这几个接口本来就该和其它
管理接口用同一个前缀，不需要专门为它们开一条新规则）：

```java
@GetMapping("/api/internal/audit/{conversationId}/verify")
@SaCheckPermission("audit:trace:verify")
public TraceAuditVerificationResponse verify(@PathVariable String conversationId) { ... }
```

路径改动需要同步检查前端是否有硬编码调用这个地址（`grep -rn "internal/audit" frontend/src`），
这个接口目前是内部运维用途，大概率没有前端调用点，但要实际确认一遍，不能假设。

## 3. 权限码设计

跟 §1 的命名规则走，`module:resource:action`：

| Controller | 端点 | 权限码 |
|---|---|---|
| `GoldenCaseController` | `GET /agent/v1/evaluation/cases` | `golden:case:view` |
| | `POST /agent/v1/evaluation/cases` | `golden:case:create` |
| | `PUT /agent/v1/evaluation/cases/{id}` | `golden:case:update` |
| | `DELETE /agent/v1/evaluation/cases/{id}` | `golden:case:delete` |
| `GoldenCandidateController` | `GET /agent/v1/evaluation/conversations` | `golden:candidate:view` |
| | `GET /agent/v1/evaluation/conversations/{id}/candidates` | `golden:candidate:view`（同一个码——两个端点都是"浏览候选用例"这同一个操作的两步，拆两个码没有实际区分度） |
| `TraceAuditController` | `GET /api/internal/audit/{conversationId}/verify`（路径已按 §2 调整） | `audit:trace:verify` |

## 4. 代码改动

### 4.1 `GoldenCaseController.java`

```java
@RestController
public class GoldenCaseController {
    private final GoldenCaseService caseService;

    public GoldenCaseController(GoldenCaseService caseService) { this.caseService = caseService; }

    @GetMapping("/agent/v1/evaluation/cases")
    @SaCheckPermission("golden:case:view")
    public List<GoldenCaseView> list() { return caseService.listAll(); }

    @PostMapping("/agent/v1/evaluation/cases")
    @SaCheckPermission("golden:case:create")
    public GoldenCaseView create(@RequestBody GoldenCaseRequest request) { return caseService.create(request); }

    @PutMapping("/agent/v1/evaluation/cases/{id}")
    @SaCheckPermission("golden:case:update")
    public GoldenCaseView update(@PathVariable String id, @RequestBody GoldenCaseRequest request) {
        return caseService.update(id, request);
    }

    @DeleteMapping("/agent/v1/evaluation/cases/{id}")
    @SaCheckPermission("golden:case:delete")
    public void delete(@PathVariable String id) { caseService.delete(id); }
}
```

现有类注释"鉴权沿用既有的 evaluation 路由策略（登录即可，见 GoldenEvaluationController）"已经不
准确（这次改完之后不再是"登录即可"），改成"细粒度权限见 §3"类似的描述，不要留一句和代码不一致
的注释。

### 4.2 `GoldenCandidateController.java`

两个方法都加 `@SaCheckPermission("golden:candidate:view")`。

### 4.3 `TraceAuditController.java`

按 §2 把路径改成 `/api/internal/audit/{conversationId}/verify`，方法加
`@SaCheckPermission("audit:trace:verify")`。

### 4.4 `schema.sql` 权限种子数据

在 342-353 行的 `INSERT INTO sys_permission` 语句里追加三行（id 接续现有最大值 10）：

```sql
       (11, 'golden:case:view', '查看评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (12, 'golden:case:manage', '管理评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
```

**先验证一个命名细节**：`create`/`update`/`delete` 三个操作要不要拆三个权限码，还是像
`sys:user:*` 那样拆细（`sys:user:create`/`update`/`delete` 各自独立）？现有 sys 模块的惯例是
**拆细**（用户管理有 5 个独立权限码），跟这个惯例走，`golden:case:create`/`golden:case:update`/
`golden:case:delete` 各自一条，不要图省事合并成一个 `golden:case:manage`（上面示例里的 `manage`
写法是错的，正式实现要按 §3 表格拆成 3 条，这里特意留一个错误示例是提醒实现时不要复制黏贴表面
相似但和现有粒度不一致的写法）——实际要插入的是：

```sql
       (11, 'golden:case:view', '查看评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (12, 'golden:case:create', '新增评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (13, 'golden:case:update', '编辑评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (14, 'golden:case:delete', '删除评测用例', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (15, 'golden:candidate:view', '浏览评测候选会话', 'Golden评测', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (16, 'audit:trace:verify', '校验审计哈希链', '审计', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
```

不需要另外改 `sys_role_permission` 的 insert（见 §1 第 2 点，兜底语句自动生效）。

## 5. Testing Decisions

- 权限拦截测试参照现有惯例（`SkillControllerTest` 明确写了"权限校验是方法级 AOP 注解，脱离
  Spring 容器直接调用不会触发，权限码是否生效由 `@SaCheckPermission` 本身的现成机制保证，不用
  重复测"）——这几个 Controller 的单测继续走同一个套路（mock service，直接 new controller 调用，
  只测参数转译对不对），**不需要**为"权限码生效"这件事本身写测试。
- 权限码本身是否生效，靠一次集成/手工验证：非 admin 登录态（比如 `analyst_test`，`db/schema.sql`
  里已有的测试账号，只挂了 `analyst` 角色、没有任何 `golden:*`/`audit:*` 权限）调用这三个
  Controller 的每个端点应该收到 403（`SaTokenExceptionHandler.forbidden` 已经把
  `NotPermissionException` 转成 `AUTH_FORBIDDEN`）；`admin` 账号应该正常放行。
- **专门验证 `TraceAuditController` 路径改动前后的鉴权状态**：改动前，未登录直接 curl
  `/internal/audit/{id}/verify` 应该也能拿到 200（复现"完全公开"这个问题）；改动后同样未登录
  请求 `/api/internal/audit/{id}/verify` 应该收到 401（未登录）而不是 200。这条测试比"403 vs
  200"更重要，因为它验证的是"请求到底有没有经过鉴权拦截器"这个更基础的前提。
- 覆盖场景：`GoldenCaseController` 的增删改各一次、`GoldenCandidateController` 两个浏览端点、
  `TraceAuditController` 的校验接口——和票面要求的覆盖范围一致。

## Out of Scope

- `GoldenEvaluationController`（`/agent/v1/evaluation/run`/`/{taskId}`/`/history`）同样完全没有
  权限注解，问题性质和这一票一样，但不在题面列出的三个类范围内，本票不动它。发现的人可以照抄
  这一票的模式单独开一个小改动，不需要等下一轮排期评审。
- 前端页面级别的权限展示（比如根据权限码动态隐藏按钮）——这一票只做后端强制校验，前端 UX
  层面的权限感知不在范围内。
- `sys_permission`/`sys_role_permission` 之外的权限模型改造（比如引入数据范围过滤到 Golden Case
  层面）——现有 `data_scope` 机制是给 sys 模块业务数据用的，Golden Case/审计数据是否需要同样的
  按部门/按人过滤,这次不评估,只补最基本的"管理员才能操作"这一层。
