# Ticket 06／18：把业务分析数据导进现有库 + 只读数据源 — 技术开发文档

> GitHub issue: [#52](https://github.com/renjian-pro/AgentTrail/issues/52)

> 派生自 [`backend-phase2-sql-dataagent.md`](backend-phase2-sql-dataagent.md) 第 5.12 节。这是 Phase 2B 的**第一张票，Ticket 07-13 全部 `Blocked by` 它**。

## 0. 范围边界

**这一票只做**：
1. 把 sakila 业务表导进**现有的 `agenttrail` 库**（不新建库、不新建容器）
2. 改造：删视图、删 `staff`/`store`、给 `rental`/`payment` 加归属列、建 `user_profile` 和 `dim_dept`
3. 业绩归属回填到**现有的** `sys_user.id` / `sys_dept.id`
4. 补 SELF 档种子账号（现在 `employee` 角色没人挂，四档 scope 缺一档）
5. **只授权这批业务表的只读账号**（合并成一个库之后，这一节是整票最关键的安全设计）
6. 独立的 `analyticsDataSource` Bean + 集成测试基座

**这一票不做**：M-Schema 自省（07）、任何 `ToolCallback`（07-12）、SQL 校验/权限改写/脱敏（09/10/11）。

判断标准：做完之后项目里**一个新的 Agent 工具都没有**，只是现有库里多了一批业务表，外加一个只能读这批表的数据源。

## 1. 为什么用同一个库（以及这个决定的代价）

本项目已有 `agenttrail` 库，里面是 `agent_*`（会话/审计/记忆/文件/PPT 任务）和 `sys_*`（身份与权限）。业务分析数据直接导进这个库。

**收益**（都是实打实的）：
- 不用新建库、不用维护第二套连接配置
- `rental.user_id` 和 `sys_user.id`、`rental.dept_id` 和 `sys_dept.id` **在同一个库里**，天然对得上，不存在跨库 id 体系对不齐的问题；引用完整性可以用一条 JOIN 直接断言（第 7 节）

**代价（必须正面处理，不能装作没有）**：

`agent_session` 存着**所有用户的完整对话历史**，`agent_trace` 存着每轮的 Prompt 和工具调用，`sys_user.password` 是 BCrypt 哈希。这些现在和业务表**在同一个 catalog 下**。如果只读账号拿到 `GRANT SELECT ON agenttrail.*`，那么：

> 用户问一句"帮我看看 agent_session 表里有什么"，DataAgent 会老老实实 `list_tables` → `describe_tables` → `execute_sql`，然后把别人的对话历史打印出来。数据权限改写救不了——`agent_session` 没配权限规则；脱敏也救不了——脱敏只管配置过的列。

所以**授权范围就是这一票的安全边界**，必须逐表授权、且只授权这批业务表（第 5 节）。这是"合并成一个库"这个决定唯一需要额外付出的东西，做对了就没有残留风险。

### 1.1 表名冲突检查

sakila 的表：`actor`/`address`/`category`/`city`/`country`/`customer`/`film`/`film_actor`/`film_category`/`film_text`/`inventory`/`language`/`payment`/`rental`（`staff`/`store` 会被删掉）。

现有 `agenttrail` 的表：`agent_file`/`agent_memory`/`agent_pause_state`/`agent_session`/`agent_skill`/`agent_trace`/`ppt_generation_task`/`sys_dept`/`sys_permission`/`sys_role`/`sys_role_permission`/`sys_user`/`sys_user_dept`/`sys_user_role`。

**无冲突。** 导入前仍然要跑一遍 `SHOW TABLES` 确认（万一后续有人加了新表）。

## 2. 导入 sakila

sakila 是 MySQL 官方公开示例数据集。从官方站点/GitHub 镜像拿 `sakila-schema.sql` + `sakila-data.sql`。

**开工前先验证**：确认脚本能在 MySQL 8.0 上执行成功，且灌完后 `rental` 约 1.6 万行、`payment` 约 1.6 万行。行数量级对不上说明脚本不完整，后面 EXPLAIN 预检（Ticket 11）的阈值和 Golden Tasks（Ticket 13）的预期值都会失去意义。

```bash
# 注意：sakila-schema.sql 开头有 CREATE DATABASE sakila / USE sakila，
# 导进 agenttrail 之前必须把这两行去掉，否则会建出一个新库来
docker exec -i llmentor-mysql mysql -uroot -proot agenttrail < sakila-schema-no-createdb.sql
docker exec -i llmentor-mysql mysql -uroot -proot agenttrail < sakila-data.sql
```

> **不要**把 sakila 的建表和数据放进 `src/main/resources/db/schema.sql`。那个文件由 `spring.sql.init` 每次启动执行，塞 1.6 万行 INSERT 进去会让每次启动都变慢，而且它的定位是"应用自己的表结构"。业务数据导入是**一次性运维操作**，脚本单独放，见第 3 节。

## 3. 改造脚本（新增 `src/main/resources/db/analytics-import.sql`）

命名用 `analytics-import` 而不是 `analytics-schema`，明确它是**一次性导入脚本**，不参与应用启动。文件头写清楚这一点。风格对齐现有 `db/schema.sql`：中文注释解释**为什么**，不复述字段名。

### 3.1 删掉会干扰权限模型的对象

```sql
-- sakila 自带 7 个视图全部删除。理由不是"用不上"，是安全：视图内部已经做完 JOIN 和聚合，
-- DataScopeRewriter（Ticket 10）拿到的 AST 里只看得到视图名、看不到底层真实表，
-- 没有任何地方可以注入 dept_id/user_id 过滤条件——一条查视图的 SQL 会静默绕过整套行级权限。
-- 汇总类查询统一改走基础表 + 显式 JOIN。
DROP VIEW IF EXISTS customer_list, film_list, nicer_but_slower_film_list,
                    sales_by_film_category, sales_by_store, staff_list, actor_info;

-- staff/store 是 sakila 自带的"员工/门店"维度，和本项目已有的 sys_user/sys_dept 是两套
-- 语义重叠的组织模型，同时留着会让模型分不清"该按 staff_id 还是 user_id 归属"。
-- 业绩归属统一走下面新增的 user_id/dept_id。
SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS staff, store;
SET FOREIGN_KEY_CHECKS = 1;
```

> 删表前要先处理 `rental`/`payment`/`customer`/`inventory` 上指向 `staff`/`store` 的外键约束（`ALTER TABLE ... DROP FOREIGN KEY ...`）。**外键名以实际 `SHOW CREATE TABLE` 输出为准，不要凭记忆写。**

### 3.2 加归属列

```sql
-- rental/payment 是两张"有业务归属"的事实表——每一笔租赁/付款都由某个业务员在某个部门经手。
-- 这两列是 DataScopeRewriter（Ticket 10）唯一的注入锚点：
--   scope=DEPT/DEPT_AND_SUB → WHERE dept_id IN (...)
--   scope=SELF              → WHERE user_id = ?
-- 因为和 sys_user/sys_dept 同库，这两列就是真正的外键语义（不建 FK 约束是为了导入方便，
-- 但第 7 节有校验断言保证引用完整性和两列的自洽）。
-- customer/inventory/film 这些是主数据（不属于任何部门），刻意不加这两列——
-- Ticket 10 对主数据表不注入条件，这是正确行为不是遗漏。
ALTER TABLE rental  ADD COLUMN user_id BIGINT NULL COMMENT '经手业务员，对应 sys_user.id',
                    ADD COLUMN dept_id BIGINT NULL COMMENT '归属部门，对应 sys_dept.id';
ALTER TABLE payment ADD COLUMN user_id BIGINT NULL COMMENT '经手业务员，对应 sys_user.id',
                    ADD COLUMN dept_id BIGINT NULL COMMENT '归属部门，对应 sys_dept.id';

CREATE INDEX idx_rental_dept  ON rental (dept_id);
CREATE INDEX idx_rental_user  ON rental (user_id);
CREATE INDEX idx_payment_dept ON payment (dept_id);
CREATE INDEX idx_payment_user ON payment (user_id);
```

索引不是可选项：Ticket 11 的 EXPLAIN 预检查会拦"预计扫描行数过大"的查询，没有这两个索引，**每一条带权限条件的查询都会被自己的预检查拦下来**。

#### 为什么两列都要，`dept_id` 不能从 `user_id` 推出来

看起来 `dept_id` 是冗余的——给了 `user_id`，查 `sys_user_dept` 不就知道他在哪个部门了？**不能这么做**，三个理由：

1. **语义不同，会随时间分叉。** `user_id` 是"这笔单子谁经手的"，`dept_id` 是"业绩算在哪个部门头上"。插入那一刻两者一致，但人员调动后就分叉了：sales_a1 从部门 3 调到部门 4，如果 `dept_id` 是按**当前**的 `sys_user_dept` 推出来的，他去年做的单子会追溯性地从部门 3 的业绩里消失、加到部门 4 上——**去年的所有报表全变了**。存成事实列，历史归属才是稳定的。这是标准的缓慢变化维（SCD）问题。
2. **多部门用户根本推不出来。** `cross_analyst` 同时挂部门 3 和 4，只给 `user_id`，这笔单子算哪个部门的？没有非任意的答案。
3. **推导在授权范围内做不到。** 按第 5 节的设计，分析账号读不到任何 `sys_*` 表，写不出这个子查询；就算能写，每条查询多一次关联也比一个走索引的 `dept_id IN (...)` 贵得多。

**和 `user_profile.dept_id` 的性质区别**（不要混为一谈）：`rental`/`payment` 上的 `dept_id` 是**事实列**（记录交易发生时的归属，本来就该冻结）；`user_profile` 上的那一列是**派生缓存**（这个人当前在哪个部门），确实会随人员调动过期，需要重新同步——它存在纯粹是因为分析账号读不到 `sys_user_dept`（第 3.3 节已说明）。

**两列的自洽没有任何结构约束保证**：回填脚本写错（比如 user 6 配了 dept 4）不会有人报错。第 7 节有一条校验断言专门查这个。

### 3.3 员工档案表（脱敏演示用）

脱敏（Ticket 11）需要真的有敏感字段可脱。sakila 原生没有，建一张：

```sql
-- 给脱敏演示用：id_card/home_address 是 mask-fields 的配置目标。
--
-- dept_id 是刻意的冗余列，必须有：部门归属的真相在 sys_user_dept，但按第 5 节的
-- 授权设计，analytics_ro 读不到任何 sys_* 表——权限改写（Ticket 10）没法用
-- EXISTS 子查询过去。把"这个人属于哪个部门"冗余一份进业务表，DEPT 档的过滤
-- 才能在授权范围内完成，同时让 rental/payment/user_profile 三张表用同一条权限规则。
-- 代价：调整用户部门后这一列会过期，需要重跑第 4.3 节的回填。演示项目里部门关系
-- 不变，可以接受；真实系统要么定时同步要么改成事件驱动更新。
CREATE TABLE IF NOT EXISTS user_profile (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    user_id      BIGINT      NOT NULL COMMENT '对应 sys_user.id',
    dept_id      BIGINT      NOT NULL COMMENT '所属部门，冗余自 sys_user_dept',
    real_name    VARCHAR(50) NOT NULL COMMENT '真实姓名',
    id_card      CHAR(18)    NOT NULL COMMENT '身份证号（敏感，必须脱敏）',
    home_address VARCHAR(200)         COMMENT '家庭住址（敏感，必须脱敏）',
    age          INT,
    education    VARCHAR(20),
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_profile_user (user_id),
    KEY idx_user_profile_dept (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '员工档案（脱敏演示用）';
```

身份证号**用明显是假的测试数据**（比如 `11010119900101000X` 这类），不要用任何真实号码。给第 4 节的 9 个种子用户各插一条，`dept_id` 填该用户挂载的部门（`cross_analyst` 挂了两个，取其一即可——它是分析岗，档案归属不是多值场景）。

### 3.4 部门维度表（让"华东区业绩"这种问题能回答）

因为 `sys_dept` 不在授权范围内（第 5 节），DataAgent 只能看到 `dept_id` 这个数字，**没法把它翻译成部门名**——用户问"华东区的业绩"，模型无从下手；反过来它给出的结论也只能说"部门 3"，可读性很差。

解法是把部门维度也作为一张**业务表**导进来：

```sql
-- sys_dept 的只读快照。不是重复设计——sys_dept 是身份体系的一部分（会被
-- 权限逻辑写入/修改），这张是分析视角的维度表，只读、只含分析需要的字段。
-- 让 DataAgent 能把 dept_id 翻译成"华东区"，也能按部门名反查 id。
CREATE TABLE IF NOT EXISTS dim_dept (
    dept_id   BIGINT       NOT NULL COMMENT '对应 sys_dept.id',
    dept_name VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id BIGINT       NOT NULL COMMENT '上级部门 id，顶级为 0',
    PRIMARY KEY (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT '部门维度（sys_dept 的分析用只读快照）';

INSERT INTO dim_dept (dept_id, dept_name, parent_id)
SELECT id, name, parent_id FROM sys_dept
ON DUPLICATE KEY UPDATE dept_name = VALUES(dept_name), parent_id = VALUES(parent_id);
```

`dim_dept` **不加权限规则**（Ticket 10 里登记成主数据）：部门名称本身不是机密，任何人都该能看到组织结构；真正受控的是"哪些部门的业绩数据能看"，那由 `rental.dept_id` 上的条件保证。

同样的同步代价：部门改名后要重跑这段 INSERT。演示项目可接受，真实系统按需做定时同步。

## 4. 业绩归属回填（对齐现有种子数据）

### 4.1 现有种子数据

`agenttrail` 库现状（Phase 2A 交付）：

| 用户 | id | 角色 | data_scope | 挂载部门 |
|---|---|---|---|---|
| admin | 1 | admin | ALL | 1 |
| mgr_test | 2 | manager | DEPT_AND_SUB | 2 |
| analyst_test | 3 | analyst | DEPT | 3 |
| cross_analyst | 4 | analyst | DEPT | **3, 4**（跨部门） |

部门树：`1(根) → 2(二级) → 3, 4(三级)`；`1(根) → 5(二级)`

角色 id 1-4 是 `admin`/`manager`/`analyst`/`employee`，data_scope 依次 `ALL`/`DEPT_AND_SUB`/`DEPT`/`SELF`。

**缺口：`employee`（SELF）角色一个用户都没挂**，四档 scope 只能演示三档。

### 4.2 补 SELF 档账号（改 `db/schema.sql` 种子）

加 5 个业务员，其中**部门 3 故意放两个**——这样 `analyst_test`（DEPT，看整个部门 3）和单个业务员（SELF，只看自己）的结果才有差异，否则两者数字相同，SELF 这一档测不出来：

| 新增用户 | id | 角色 | 挂载部门 |
|---|---|---|---|
| sales_east | 5 | employee(SELF) | 2（二级部门直属） |
| sales_a1 | 6 | employee(SELF) | 3 |
| sales_a2 | 7 | employee(SELF) | 3 |
| sales_b | 8 | employee(SELF) | 4 |
| sales_south | 9 | employee(SELF) | 5 |

**部门 2 直属放一个业务员**也是刻意的：否则 `mgr_test` 的 `DEPT_AND_SUB`（子树 {2,3,4}）和 `cross_analyst` 的 `DEPT`（{3,4}）结果完全相同，`DEPT_AND_SUB` 这一档就白测了。

密码用 BCrypt，和现有种子账号同一套生成方式。种子 INSERT 写成幂等的（对齐现有种子写法），否则每次启动主键冲突。

**顺手清理**：`sys_role` 里有一条 id=6 的垃圾数据 `it_role_213778866114400`（集成测试没清理干净留下的）。这一票删掉，并检查对应测试是否需要补 teardown。

### 4.3 回填规则

```sql
-- 确定性取模分配：同一个脚本反复执行结果永远一致，Golden Tasks（Ticket 13）的
-- 预期数字才写得死。用 RAND() 会让评测集永远对不上。
UPDATE rental r
JOIN (
    SELECT 0 AS slot, 5 AS user_id, 2 AS dept_id
    UNION ALL SELECT 1, 6, 3
    UNION ALL SELECT 2, 7, 3
    UNION ALL SELECT 3, 8, 4
    UNION ALL SELECT 4, 9, 5
) m ON m.slot = r.customer_id MOD 5
SET r.user_id = m.user_id, r.dept_id = m.dept_id;

-- payment 按 rental_id 继承 rental 的归属，保证两张表口径一致
-- （同一笔租赁的付款不该归到另一个业务员名下）
UPDATE payment p JOIN rental r ON r.rental_id = p.rental_id
SET p.user_id = r.user_id, p.dept_id = r.dept_id;
```

回填完验证 `SELECT COUNT(*) FROM rental WHERE user_id IS NULL` 和 payment 同样查询都返回 0。

### 4.4 权限演示阶梯

回填后各档 scope 的 `SELECT COUNT(*) FROM rental` 应该**严格递减**：

| 账号 | scope | 可见范围 | 预期行数 |
|---|---|---|---|
| admin | ALL | 全部 | 全量（约 16044） |
| mgr_test | DEPT_AND_SUB | 子树 {2,3,4} → slot 0,1,2,3 | 约 4/5 |
| cross_analyst | DEPT | {3,4} → slot 1,2,3 | 约 3/5 |
| analyst_test | DEPT | {3} → slot 1,2 | 约 2/5 |
| sales_a1 | SELF | user_id=6 → slot 1 | 约 1/5 |

> 上面是按 `customer_id MOD 5` 的**理论比例**。`customer_id` 在 rental 里分布不完全均匀，**实际数字必须回填完之后实测**，把五个真实数字记进这份文档的追加说明——Ticket 13 的 Golden Tasks 直接引用它们。**不要**把这里的估算值当预期值写进测试。

## 5. 只读账号：按表 + 按列授权（合并成一个库之后最关键的一节）

因为业务表和 `agent_*`/`sys_*` 同库，**绝对不能** `GRANT SELECT ON agenttrail.*`（理由见第 1 节）。改成显式列出可读对象：

**授权范围就是这一票导进来的那批业务表，一张不多。**

```sql
CREATE USER IF NOT EXISTS 'analytics_ro'@'%' IDENTIFIED BY '<自己定，写进 application-local.yml>';

-- 业务事实表
GRANT SELECT ON agenttrail.rental        TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.payment       TO 'analytics_ro'@'%';
-- 主数据
GRANT SELECT ON agenttrail.customer      TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.inventory     TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film          TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_actor    TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_category TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.film_text     TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.actor         TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.category      TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.language      TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.address       TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.city          TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.country       TO 'analytics_ro'@'%';
-- 本票新建的两张
GRANT SELECT ON agenttrail.user_profile  TO 'analytics_ro'@'%';
GRANT SELECT ON agenttrail.dim_dept      TO 'analytics_ro'@'%';

FLUSH PRIVILEGES;
```

**明确一张都不授予的**（不是遗漏，是设计）：

| 表 | 不授权的理由 |
|---|---|
| `agent_session` / `agent_trace` / `agent_memory` / `agent_pause_state` / `agent_file` | 所有用户的完整对话历史、每轮 Prompt、工具调用记录——泄漏面最大的一批 |
| `ppt_generation_task` / `agent_skill` | 同上，别人的任务和产物 |
| `sys_user` / `sys_dept` / `sys_user_dept` / `sys_user_role` | 身份体系。分析需要的那部分已经以 `user_profile`/`dim_dept` 的形式导成业务表了（第 3.3/3.4 节），原表不必也不该暴露——尤其 `sys_user.password` 哪怕是哈希也没有任何理由让分析账号看到 |
| `sys_role` / `sys_permission` / `sys_role_permission` | 权限配置本身。让 DataAgent 能读它没有业务价值，只增加攻击面 |

> 因为 `sys_*` 全都不授权，Ticket 10 的权限改写**不能**用 `EXISTS (SELECT 1 FROM sys_user_dept ...)` 这类子查询——这正是 `user_profile` 要冗余一个 `dept_id` 列的原因（第 3.3 节）。三张有归属的表（`rental`/`payment`/`user_profile`）因此都能用同一条简单规则，反而更好写。

### 5.1 为什么这套授权是主防线，配置排除只是第二层

Ticket 07 的 `exclude-tables` 配置也能让 `agent_*` 不出现在 M-Schema 里。但那是**应用层的**——配置写错、新表忘了加进排除名单、或者模型直接猜一个表名硬查，都会绕过它。

数据库授权是**唯一在所有绕过路径下都成立**的那一层：账号没权限，`DatabaseMetaData.getTables()` 里根本不会出现那些表（模型看不到），直接 `SELECT * FROM agent_session` 也会被 MySQL 拒绝（模型猜也没用）。

两层都要有，但要清楚哪一层是真正的边界。

### 5.2 验证

授权完必须逐条验证，写进验收标准：
- `analytics_ro` 能 `SELECT COUNT(*) FROM rental`、能 `SELECT * FROM dim_dept`
- `SELECT * FROM agent_session` **被拒绝**
- `SELECT * FROM sys_user` **被拒绝**
- `SELECT * FROM sys_user_dept` **被拒绝**
- `DELETE FROM rental WHERE 1=0` **被拒绝**
- 用 `analytics_ro` 连上后跑 `DatabaseMetaData.getTables()`，**返回的表名集合恰好等于授权列表**（这一条是 Ticket 07 M-Schema 自省的前提：账号看不到的表，模型也就无从知道它存在）

## 6. 只读数据源 Bean

| 文件 | 职责 |
|---|---|
| `com/agenttrail/capability/analytics/config/AnalyticsDataSourceProperties.java` | `@ConfigurationProperties("agenttrail.analytics.datasource")`：`enabled`/`jdbcUrl`/`username`/`password`/`maximumPoolSize`/`connectionTimeoutMs` |
| `com/agenttrail/capability/analytics/config/AnalyticsDataSourceConfig.java` | `@Configuration` + `@ConditionalOnProperty(...name="enabled", havingValue="true")`，产出 `@Bean("analyticsDataSource") DataSource` |

### 6.1 同一个库，为什么还要第二个连接池（写进类 Javadoc）

`jdbc-url` 和主 `DataSource` 指向同一个 database，但**用户不同**，这就是全部理由：

1. **凭据不同**：主 `DataSource` 用的是能写 `agent_session` 的账号；分析走 `analytics_ro`，只能读第 5 节列出的那些表。**同一个连接池不可能同时是两种权限**——这是把授权做成安全边界的前提，没有第二个池就没法用第二个账号。
2. **故障域隔离**：一条分析慢查询打满连接时，主库连接池不受影响，会话历史照常读写。
3. **参数不同**：分析按"交互式分析"调（短连接超时、快速失败给模型反馈），主库按"事务型写入"调。

```java
@Bean("analyticsDataSource")
public DataSource analyticsDataSource(AnalyticsDataSourceProperties properties) {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(properties.jdbcUrl());     // 和主库同一个 database
    config.setUsername(properties.username());   // 但用 analytics_ro
    config.setPassword(properties.password());
    config.setMaximumPoolSize(properties.maximumPoolSize());
    config.setConnectionTimeout(properties.connectionTimeoutMs());
    // 连接池层面也标只读——这只是提示（MySQL 驱动对该标志的处理有已知缺陷，
    // 见 Ticket 11 第 5.2 节），真正的只读保证是第 5 节的账号权限
    config.setReadOnly(true);
    config.setPoolName("analytics-pool");
    return new HikariDataSource(config);
}
```

`@ConditionalOnProperty` 是必须的：没导入业务数据的开发者启动应用不该失败。

### 6.2 `maximumPoolSize` 怎么定

**不要写死一个"看起来合理"的数字**，配置注释写清算法：

```
maximumPoolSize = (MySQL max_connections × 安全系数 0.8 − 主库池占用) / 应用实例数
```

现在两个池打同一个 MySQL 实例，**总连接数是两个池之和**，算的时候要把主库池也算进去。单机开发默认 **10**；多实例部署由部署环境用环境变量覆盖。来源是踩坑点 #33（单机配 50、扩到 5 实例后总连接 250 打爆数据库）——DataAgent 是最容易复现的地方，因为 SQL 查询持有连接时间长。

## 7. 验收标准

- [ ] `mvn test` 全绿
- [ ] `mvn spring-boot:run` 能启动；`agenttrail.analytics.datasource.enabled=false` 时启动完全不受影响
- [ ] `agenttrail` 库里有 sakila 业务表，`SHOW FULL TABLES WHERE Table_type='VIEW'` 返回空
- [ ] `staff`/`store` 表不存在
- [ ] `rental`/`payment` 各约 1.6 万行，`user_id`/`dept_id` **无 NULL**
- [ ] 引用完整性：`SELECT COUNT(*) FROM rental r LEFT JOIN sys_user u ON u.id=r.user_id WHERE u.id IS NULL` 返回 0；`dept_id` 对 `sys_dept` 同样断言
- [ ] **两列自洽**：每一行的 `dept_id` 必须是该 `user_id` 实际挂载的部门之一，否则 DEPT 档用户会查不到自己经手的单子、结果无法解释。断言：
  ```sql
  SELECT COUNT(*) FROM rental r
  WHERE NOT EXISTS (SELECT 1 FROM sys_user_dept ud
                    WHERE ud.user_id = r.user_id AND ud.dept_id = r.dept_id)
  ```
  必须返回 0；`payment` 同样断言。**这一条只在导入时校验**——之后人员调动导致的"不一致"是正确行为（历史归属冻结，见 3.2 节），不要把它做成运行时约束或定时任务
- [ ] `sys_user` 新增 5 个 `employee`/SELF 账号，密码是 BCrypt 哈希不是明文（人工查一眼实际存储值）
- [ ] `sys_role` 里 id=6 的垃圾角色已清理
- [ ] `dim_dept` 行数等于 `sys_dept` 行数，`dept_name` 一一对应
- [ ] **第 5.2 节的六条授权验证全部通过**（这是这一票的安全交付物）
- [ ] `analyticsDataSource` Bean 存在，和主 `DataSource` 是两个不同实例（测试断言 `!=`）
- [ ] `maximumPoolSize` 可由配置覆盖，默认 ≤ 10
- [ ] **端到端对齐验证**：对 admin/mgr_test/cross_analyst/analyst_test/sales_a1 五个账号，各自调 `DataScopeResolver.resolve(id)` 拿到 scope + deptIds，据此手写过滤条件跑在业务表上，五个行数**严格递减**；把五个实测数字记进本文档追加说明
- [ ] 应用重启两次，种子 INSERT 不报主键冲突（幂等），且**不会重新导入 sakila 数据**（导入脚本不在 `spring.sql.init` 里）

## 8. 测试基座（不用 Testcontainers）

集成测试直接连本机已有的 MySQL，**不起容器**。

理由：业务表有 1.6 万×2 行真实数据，这套数据分布本身就是测试价值的来源——权限改写、EXPLAIN 预检、结果截断只在真实数据量下才有意义。用 Testcontainers 要么每个测试类灌一份几 MB 的脚本（几十秒起步），要么造一份缩水的假数据（那就测不出真实分布下的问题）。

代价说清楚：**这些测试只在本机 MySQL 起着、且业务数据已导入的机器上跑**。这和项目现有实践一致——`*IT` 走 Failsafe，CI 默认只跑 `*Test`（见 `docs/roadmap.md` 的测试分层约定），本来就不在 CI 里跑，不会让 CI 变红。

| 文件 | 职责 |
|---|---|
| `src/test/java/com/agenttrail/capability/analytics/AnalyticsLocalDbTestSupport.java` | 用 `analytics_ro` 凭据连本机库；**连不上时用 `Assumptions.assumeTrue(...)` 跳过而不是失败** |

```java
/**
 * 用只读账号连本机 agenttrail 库，不起 Testcontainers。
 *
 * <p>刻意用 analytics_ro 而不是 root：这样集成测试跑的权限上下文和生产完全一致，
 * "某张表不该被读到"这类断言才有意义——用 root 跑测试会让第 5 节的授权设计
 * 在测试里完全失效，等于没测。
 *
 * <p>代价：这些测试只在本机数据已导入时能跑。它们是 *IT，走 Failsafe，
 * CI 默认不跑（对齐项目现有的测试分层约定），不会让 CI 变红。
 */
public abstract class AnalyticsLocalDbTestSupport {
    @BeforeAll
    static void requireLocalAnalyticsData() {
        Assumptions.assumeTrue(canConnect(), "本机业务数据不可用，跳过（需要先按 ticket-06 导入）");
    }
}
```

后续 Ticket 07/09/10/11/13 的集成测试全部复用它，**不要各自再写一套连接逻辑**。

## 9. 配置项

`application.yml`：

```yaml
agenttrail:
  analytics:
    datasource:
      enabled: false          # 默认关闭：没导入业务数据的环境启动不受影响
      # 和主库同一个 database，区别只在账号——analytics_ro 只能读业务表，
      # 读不到 agent_session/sys_user.password（授权见 ticket-06 第 5 节）
      jdbc-url: ""            # 真实值放 application-local.yml
      username: ""
      password: ""
      # 见第 6.2 节：两个池打同一个 MySQL 实例，算总量时要把主库池一起算进去
      maximum-pool-size: 10
      connection-timeout-ms: 5000
```

`application-local.yml`（已 gitignore）：

```yaml
agenttrail:
  analytics:
    datasource:
      enabled: true
      jdbc-url: "jdbc:mysql://127.0.0.1:3306/agenttrail?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8"
      username: "analytics_ro"
      password: "<第 5 节设的密码>"
```

`application-local.example.yml` 同步加一份带注释的示例。

## 10. 实现顺序

1. 第 1.1 节表名冲突检查 + 第 2 节导入 sakila（去掉 CREATE DATABASE/USE 两行），确认行数量级
2. 第 3 节改造脚本（删视图/删表/加列加索引/建 user_profile 和 dim_dept），本地跑通
3. 第 4.2 节改 `db/schema.sql` 种子（5 个 SELF 账号 + 清垃圾角色），确认幂等
4. 第 4.3 节回填 + 无 NULL + 引用完整性断言
5. **第 5 节授权 + 5.2 的六条验证**——这一步做完再往下，它是整票的安全地基
6. 第 6 节 Bean + `AnalyticsLocalDbTestSupport`（用 `analytics_ro` 连），跑通"能 COUNT 出正确行数"且"读 agent_session 被拒"
7. **第 7 节的端到端对齐验证**，把五个实测数字写进文档

## 11. 明确禁止事项

- **不要**用 `GRANT SELECT ON agenttrail.*`——只授权本票导进来的那批业务表（第 5 节）
- **不要**给 analytics_ro 授予任何 `sys_*` 或 `agent_*` 表
- **不要**把 sakila 的建表/数据 SQL 放进 `db/schema.sql`（那个文件每次启动都执行）
- **不要**保留 sakila 自带的任何视图（理由见 3.1，这是安全边界不是洁癖）
- **不要**用 `RAND()` 回填归属——Golden Tasks 需要可复现的数据分布
- **不要**把第 4.4 节的估算比例当测试预期值，必须实测
- **不要**在 `user_profile` 里放任何真实身份证号/住址
- **不要**用 root 账号跑 analytics 的集成测试（会让授权设计在测试里失效）
- **不要**复用主 `DataSource` 连业务表——第二个池是"能用第二个账号"的前提
- **不要**用 Testcontainers（第 8 节已说明）、**不要**用 H2
- **不要**把真实数据库密码写进 `application.yml` 或任何会提交的文件
- **不要**在这一票里写任何 `ToolCallback`、`SchemaProvider`、SQL 校验逻辑
- **不要**在代码注释、commit message 或文档里点名具体的参考实现来源仓库（`AGENTS.md` 披露规则）

## 12. 和现有代码的边界

**修改**：`src/main/resources/db/schema.sql`（种子数据段：加 5 个账号、清垃圾角色）、`application.yml` + `application-local.example.yml`、`AGENTS.md`（第 8 节末尾那条测试约定改成"集成测试连真实 MySQL/Redis，一律不用 H2"）。
**新增**：`src/main/resources/db/analytics-import.sql`（一次性运维脚本）、`com.agenttrail.capability.analytics.config` 包、一个测试基类。
**不碰**：任何现有 Java 文件、`com.agenttrail.sys`/`auth` 下的代码（只改种子数据不改代码）、现有 `agent_*` 表的结构和数据。
