-- AgentTrail 会话持久化表结构（MySQL 8）
--
-- 两个独立的 key 是刻意设计，不是冗余：
--   conversation_id —— 一次会话的标识，跨多轮不变。用户在同一个会话里连续提问，
--                      这个 id 一直是同一个；历史消息重建、任务单飞、跨实例中断都按它索引。
--   id              —— 单轮问答的标识（本表主键）。一轮就是一次"提问→回答"，
--                      是历史回放的最小归属单位。
--
-- 为什么必须拆成两个：附件的归属是"某一轮"而不是"整个会话"——用户在第 3 轮传的文件，
-- 回放到第 1 轮时不该出现。但流式开始的那一刻这一轮的 id 还不存在（要等这一轮结束才落库），
-- 所以文件先按 conversation_id 关联，等 Complete 事件带回 id 再补上归属。
-- 一开始就按这个结构建表，否则文件问答能力包上线时要改表（踩坑点 #49）。

CREATE TABLE IF NOT EXISTS agent_session
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，单轮问答标识，历史回放的归属单位',
    conversation_id     VARCHAR(100) NOT NULL COMMENT '会话标识，跨轮不变',
    user_id             VARCHAR(100) NULL COMMENT '提问用户，权限判定主体',
    question            LONGTEXT     NOT NULL COMMENT '用户提问',
    answer              LONGTEXT     NULL COMMENT '最终答案（正文，不含思考过程）',
    think               LONGTEXT     NULL COMMENT '模型思考过程，与答案分开存，便于前端折叠展示',
    timeline            LONGTEXT     NULL COMMENT '本轮的思考/正文/工具调用时间线，JSON 数组',
    -- 首字延迟必须独立于总耗时单独记录：流式场景下用户感知的是"多久看到第一个字"，
    -- 总耗时长但首字快的体验，远好于反过来（踩坑点 #38）
    first_response_time BIGINT       NULL COMMENT '首字延迟（毫秒）',
    total_response_time BIGINT       NULL COMMENT '整轮总耗时（毫秒）',
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    -- 历史重建永远是「按会话查最近 N 轮」，复合索引直接支撑该查询的过滤+排序，
    -- 比 conversation_id 单列索引再回表排序更省
    KEY idx_conversation_created (conversation_id, created_at),
    KEY idx_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '智能体单轮问答记录';

-- 技能（Skill）元数据表。
--
-- 这张表刻意**不存技能正文**：文件系统才是 SKILL.md 内容的唯一真相。正文动辄上千行，
-- 还带着 references/ scripts/ 一堆资源文件，塞进数据库既难维护，也让"运维直接往 skills
-- 目录扔一个文件夹"这种最省事的上线方式没法用。
--
-- 那这张表存在的意义是什么？只有一样东西是文件系统里根本没有的：enabled。
-- 其余 name/description/skill_path 都是为了列表页和装配查询而从磁盘冗余过来的，
-- 每轮定时对账都按磁盘刷新一遍，磁盘永远赢；只有 enabled 反过来，对账绝不去动它
-- （踩坑点 #48：两个数据源各自是不同东西的真相，对账的方向必须按字段分别定）。

CREATE TABLE IF NOT EXISTS agent_skill
(
    -- 用自增主键而不是应用侧生成的雪花 id：这张表的写入只有定时对账和后台运营两条低频路径，
    -- 没有分库分表和跨实例并发插入的压力，自增主键足够且更好读
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    -- 190 而不是更长：这一列上有唯一索引，utf8mb4 每字符 4 字节，190*4=760 字节稳落在
    -- 任何 InnoDB 索引键长上限之内。技能名的语义是"一个目录名"，几百字符的技能名没有意义
    name        VARCHAR(190)  NOT NULL COMMENT '技能名，等于 skills 根目录下的子目录名，也是模型调用 Skill 工具时传的值',
    -- 只作运维排查用。装配时并不信它，而是按"skills 根目录 + name"重新解析：
    -- 一是可移植（存量数据里的绝对路径带着别人机器的盘符），
    -- 二是安全（DB 可能被别的通道写脏，直接拿它存的路径读文件等于把路径穿越入口开在数据库上）
    skill_path  VARCHAR(1000) NULL COMMENT '技能目录绝对路径，仅供运维排查',
    description VARCHAR(3000) NULL DEFAULT '' COMMENT '取自 SKILL.md frontmatter，渲染进 Skill 工具的描述供模型选技能',
    enabled     TINYINT       NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用。运营在后台切换，下一轮对话立刻生效，不用重启',
    file_name   VARCHAR(255)  NULL COMMENT '上传时的原始 zip 文件名；运维直接放目录被对账发现的为 NULL',
    created_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    -- 技能名就是业务主键：对账是"扫一遍磁盘再和全表比对"的幂等操作，
    -- 唯一索引是它反复重跑也不会插出重复记录的最后一道保证
    UNIQUE KEY uk_skill_name (name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '技能元数据（正文在文件系统，这里只存启用状态和查询用字段）';

-- TraceAudit（issue #17）：每一行对应一次模型调用，不是一行一轮对话——
-- 一轮里如果有工具调用，模型调用本身和工具执行是分开的两件事，这张表只审计前者。
-- 时间相关字段一律存 epoch millis（BIGINT），不用 TIMESTAMP：和 TraceRecord/MemoryItem
-- 这两个领域对象里的字段类型直接对应，读出来不需要在 java.time 和 java.sql.Timestamp
-- 之间做一层转换。

CREATE TABLE IF NOT EXISTS agent_trace
(
    id                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id   VARCHAR(100) NOT NULL COMMENT '会话标识',
    round             INT          NOT NULL COMMENT '轮次序号，从 1 开始',
    input_data        LONGTEXT     NULL COMMENT '本轮发给模型的消息历史渲染文本',
    output_data       LONGTEXT     NULL COMMENT '本轮产出：文本轮是最终正文，工具调用轮是工具调用列表渲染文本；模型调用失败时为 NULL',
    think             LONGTEXT     NULL COMMENT '本轮思考过程，没有时为 NULL',
    prompt_tokens     BIGINT       NOT NULL COMMENT '本轮 prompt token 消耗；模型未在响应里报告时为 -1',
    completion_tokens BIGINT       NOT NULL COMMENT '本轮 completion token 消耗；模型未在响应里报告时为 -1',
    duration_millis   BIGINT       NOT NULL COMMENT '本轮耗时（毫秒）',
    success           TINYINT      NOT NULL COMMENT '本轮模型调用是否成功：1 成功 0 失败',
    error_message     LONGTEXT     NULL COMMENT '失败时的错误信息；成功时为 NULL',
    recorded_at       BIGINT       NOT NULL COMMENT '记录写入时刻（epoch millis）',
    prev_hash         VARCHAR(64)  NULL COMMENT '上一条记录的 hash，本会话第一条为 NULL',
    hash              VARCHAR(64)  NULL COMMENT 'SHA-256(关键字段拼接 + prev_hash)，写入后不可再改',
    PRIMARY KEY (id),
    -- 读回一个会话的完整 trace 永远是「按轮次正序」，复合索引直接支撑这个查询
    KEY idx_trace_conversation_round (conversation_id, round)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'TraceAudit：每次模型调用的审计记录';

-- 已有数据库需要手动执行下面两条一次性迁移；历史记录保持 NULL，不回填伪造哈希链。
-- ALTER TABLE agent_trace ADD COLUMN prev_hash VARCHAR(64) NULL COMMENT '上一条记录的 hash，本会话第一条为 NULL';
-- ALTER TABLE agent_trace ADD COLUMN hash VARCHAR(64) NULL COMMENT 'SHA-256(关键字段拼接 + prev_hash)，写入后不可再改';

-- 分层记忆中间层（issue #19）：画像/偏好/指令/事实，按 userId 整体读取，不需要按条目单独查找/删除。

CREATE TABLE IF NOT EXISTS agent_memory
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    user_id    VARCHAR(100) NOT NULL COMMENT '所属用户',
    type       VARCHAR(20)  NOT NULL COMMENT '记忆类型：PROFILE/PREFERENCE/INSTRUCTION/FACT',
    content    LONGTEXT     NOT NULL COMMENT '记忆正文',
    created_at BIGINT       NOT NULL COMMENT '提取时刻（epoch millis）',
    PRIMARY KEY (id),
    -- 读取永远是「按用户查全部」，按提取顺序返回；提取顺序用主键自增序足够，不用另建时间索引
    KEY idx_memory_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '分层记忆中间层：画像/偏好/指令/事实';

-- HITL 暂停快照（issue #13）：整份 PauseState（含消息历史、挂起工具调用、运行时参数）
-- 序列化成一段 JSON 存进 snapshot_json——它本身是一份复杂的嵌套快照，不是天然扁平的记录，
-- 拆列存储没有额外的查询收益。reason/paused_at 单独拆成列只为运维排查方便，
-- 恢复逻辑永远只读 snapshot_json，不依赖这两列的值。
--
-- 同一个会话只应该有一份暂停状态，所以 conversation_id 是唯一键，
-- 保存时用 ON DUPLICATE KEY UPDATE 覆盖，语义对应 PauseStateStore#save 的约定。

CREATE TABLE IF NOT EXISTS agent_pause_state
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    conversation_id VARCHAR(100) NOT NULL COMMENT '会话标识',
    reason          VARCHAR(50)  NOT NULL COMMENT '暂停原因，仅供运维排查；恢复逻辑读的是 snapshot_json',
    paused_at       BIGINT       NOT NULL COMMENT '暂停发生时刻（epoch millis），仅供运维排查',
    snapshot_json   LONGTEXT     NOT NULL COMMENT '完整 PauseState 序列化 JSON，恢复时的唯一真相来源',
    PRIMARY KEY (id),
    UNIQUE KEY uk_pause_conversation (conversation_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'HITL 暂停快照，同一会话只保留最新一份';

-- 文件问答（issue #21）：上传文件的元数据 + Tika 解析出的全量文本。
--
-- 沿用 agent_session 已验证过的双 key 设计（见文件开头的说明和踩坑点 #49）：
--   conversation_id —— 跨轮可见，上传时立刻知道
--   turn_id         —— 归属的单轮问答 id（指向 agent_session.id），上传发生在这一轮结束之前，
--                      所以先是 NULL，等 Complete 事件带回轮次 id 再回填（issue #28 的范围，
--                      这一票只建表占位，不实现回填）
--
-- parsed_text 无论文件是否超过 RAG 阈值都存全量文本：阈值只影响直接喂给模型多少内容
-- （见 FileQaService#contentFor），超阈值的大文件同样需要全文供后续分片向量化（issue #26）。

CREATE TABLE IF NOT EXISTS agent_file
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，单个文件的标识',
    user_id         VARCHAR(100) NULL COMMENT '所属登录用户，资源归属校验的唯一依据',
    conversation_id VARCHAR(100) NOT NULL COMMENT '会话标识，跨轮可见',
    turn_id         BIGINT       NULL COMMENT '归属的单轮问答 id（agent_session.id）；上传时为 NULL，等这一轮结束才回填',
    file_name       VARCHAR(255) NOT NULL COMMENT '原始文件名',
    content_type    VARCHAR(100) NULL COMMENT '上传时的 MIME 类型',
    size_bytes      BIGINT       NOT NULL COMMENT '文件大小（字节）',
    kind            VARCHAR(10)  NOT NULL DEFAULT 'TEXT' COMMENT '文件路由：TEXT 走 Tika 解析/RAG，IMAGE 走多模态懒加载识别（issue #27）',
    parsed_text     LONGTEXT     NULL COMMENT 'TEXT：Tika 解析全文，超 RAG 阈值同样存全量；IMAGE：多模态描述的懒缓存，未识别前为 NULL',
    raw_bytes       LONGBLOB     NULL COMMENT '图片原始字节，仅 IMAGE 类型使用，供懒加载识别时读取（issue #27）',
    created_at      BIGINT       NOT NULL COMMENT '上传时刻（epoch millis）',
    PRIMARY KEY (id),
    -- 按会话查全部附件（历史回放、issue #28 的分组渲染）走这个索引
    KEY idx_file_conversation (conversation_id),
    KEY idx_file_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT '文件问答：上传文件的元数据 + 正文（Tika 解析文本或图片识别描述）';

-- 注意：CREATE TABLE IF NOT EXISTS 只在表不存在时生效——issue #21/#26 阶段已经建好的
-- agent_file 老表不会因为这次改了列定义就自动加上 kind/raw_bytes。MySQL（不同于 Postgres）
-- 不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS 语法，没有一条能在这里直接照抄的幂等语句；
-- 这个项目目前还没有真实生产数据，issue #27 上线时手动 DROP TABLE agent_file 一次即可让它
-- 用新列定义重建。真的有生产数据需要保留时，要么手写"先查 information_schema 再决定要不要
-- ALTER"的存储过程，要么引入 Flyway/Liquibase 这类迁移工具——这两者都不是这一票的范围。

-- PPT 生成状态机（issue #24）：这一行就是断点续传的 checkpoint 本体，按状态粒度
-- （不是子步骤粒度，踩坑点 #44）——status/error_msg 记录"跑到哪一步、上一次失败没有"，
-- context_json 记录"跑到这一步为止，已经产出的全部数据"，两者合起来才能真正从断点继续，
-- 只有 status 没有 context_json 的话，恢复时会丢失前面几个状态已经生成的 requirement/
-- outline/schema，等于又要重新调一遍模型。
--
-- context_json 整体存一段 JSON 而不是拆列，原因和 agent_pause_state 表一样：PptGenerationContext
-- 是一份逐步累积的嵌套快照（结构化的 requirement/outline/schema），不是天然扁平的记录，
-- 拆列存储没有额外的查询收益。

CREATE TABLE IF NOT EXISTS ppt_generation_task
(
    id              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键，单个 PPT 生成任务的标识',
    user_id         VARCHAR(100) NULL COMMENT '所属登录用户，恢复与下载必须做归属校验',
    conversation_id VARCHAR(100) NOT NULL COMMENT '发起这个任务的会话标识',
    status          VARCHAR(20)  NOT NULL COMMENT '当前状态（PptState 枚举名），即断点续传的 checkpoint',
    error_msg       LONGTEXT     NULL COMMENT 'status 对应状态上一次执行失败的错误信息；成功推进到这个状态时为 NULL',
    cancel_requested BOOLEAN      NOT NULL DEFAULT FALSE COMMENT '用户请求取消，状态机在下一个状态边界转为 CANCELLED',
    context_json    LONGTEXT     NOT NULL COMMENT 'PptGenerationContext 完整快照 JSON，恢复时从这里重建上下文',
    created_at      BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at      BIGINT       NOT NULL COMMENT '最近一次状态推进/失败记录的时刻（epoch millis）',
    PRIMARY KEY (id),
    -- 运维排查"某个会话发起过哪些 PPT 任务"走这个索引；issue #32 起 MODIFY/RESUME 分支的
    -- findLatestByConversationId 查询（按 conversation_id 找最新一条任务）也走这个索引——
    -- crash 恢复场景（已知 taskId 时）仍然只按主键 id 查找，两条路径不冲突
    KEY idx_ppt_task_conversation (conversation_id),
    KEY idx_ppt_task_user (user_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT 'PPT 生成状态机任务：status/error_msg 是按状态粒度的断点续传 checkpoint';

-- 用户体系采用五张标准 RBAC 表：用户与部门多对多，角色上的 data_scope 决定后续
-- 数据分析能力包能看到的范围。认证 token 不把权限事实复制到 token 内，角色变更可以即时生效。
CREATE TABLE IF NOT EXISTS sys_dept
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    name       VARCHAR(100) NOT NULL COMMENT '部门名称',
    parent_id  BIGINT       NOT NULL DEFAULT 0 COMMENT '父部门 id，顶级部门为 0',
    ancestors  VARCHAR(500) NOT NULL DEFAULT '0' COMMENT '祖先路径，逗号分隔 id 链',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '最近更新时刻（epoch millis）',
    PRIMARY KEY (id),
    KEY idx_sys_dept_parent (parent_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '部门树';

CREATE TABLE IF NOT EXISTS sys_role
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(64)  NOT NULL COMMENT '角色代码',
    name       VARCHAR(64)  NOT NULL COMMENT '角色名称',
    data_scope VARCHAR(32)  NOT NULL DEFAULT 'DEPT' COMMENT '数据范围：ALL/DEPT_AND_SUB/DEPT/SELF',
    sort       INT          NOT NULL DEFAULT 0 COMMENT '显示顺序',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '最近更新时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_role_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '角色';

CREATE TABLE IF NOT EXISTS sys_user
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    username   VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password   VARCHAR(128) NOT NULL COMMENT 'BCrypt 密码哈希',
    nickname   VARCHAR(64)  NULL COMMENT '昵称',
    status     VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE/DISABLED',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    updated_at BIGINT       NOT NULL COMMENT '更新时间（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '用户';

CREATE TABLE IF NOT EXISTS sys_user_role
(
    user_id    BIGINT NOT NULL COMMENT '用户 id',
    role_id    BIGINT NOT NULL COMMENT '角色 id',
    created_at BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    PRIMARY KEY (user_id, role_id),
    KEY idx_sys_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '用户角色关联';

CREATE TABLE IF NOT EXISTS sys_user_dept
(
    user_id    BIGINT NOT NULL COMMENT '用户 id',
    dept_id    BIGINT NOT NULL COMMENT '部门 id',
    created_at BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    PRIMARY KEY (user_id, dept_id),
    KEY idx_sys_user_dept_dept (dept_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '用户部门关联';

-- 认证与权限种子数据。密码统一为开发环境测试密码 password，数据库内只保留 BCrypt 哈希。
INSERT INTO sys_dept (id, name, parent_id, ancestors, sort, status, created_at, updated_at)
VALUES (1, '集团', 0, '0', 1, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (2, '技术中心', 1, '0,1', 1, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, '产品部', 2, '0,1,2', 1, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (4, '平台部', 2, '0,1,2', 2, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (5, '运营中心', 1, '0,1', 2, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE name = VALUES(name), parent_id = VALUES(parent_id), ancestors = VALUES(ancestors), updated_at = VALUES(updated_at);

INSERT INTO sys_role (id, code, name, data_scope, sort, status, created_at, updated_at)
VALUES (1, 'admin', '系统管理员', 'ALL', 1, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (2, 'manager', '部门经理', 'DEPT_AND_SUB', 2, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, 'analyst', '数据分析师', 'DEPT', 3, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (4, 'employee', '普通员工', 'SELF', 4, 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE name = VALUES(name), data_scope = VALUES(data_scope), updated_at = VALUES(updated_at);

INSERT INTO sys_user (id, username, password, nickname, status, created_at, updated_at)
VALUES (1, 'admin', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '系统管理员', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (2, 'mgr_test', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '测试经理', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, 'analyst_test', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '测试分析师', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (4, 'cross_analyst', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '跨部门分析师', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (5, 'sales_east', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '东区业务员', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (6, 'sales_a1', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '产品部业务员一', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (7, 'sales_a2', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '产品部业务员二', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (8, 'sales_b', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '平台部业务员', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (9, 'sales_south', '$2a$10$ChGGCI7lmqec.PEdFjLuOu/D6da7whq7pw9WhupB6Naor0dVK3sQ6', '南区业务员', 'ACTIVE', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE password = VALUES(password), nickname = VALUES(nickname), status = VALUES(status), updated_at = VALUES(updated_at);

INSERT INTO sys_user_role (user_id, role_id, created_at)
VALUES (1, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (2, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (4, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (5, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (6, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (7, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (8, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (9, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

INSERT INTO sys_user_dept (user_id, dept_id, created_at)
VALUES (1, 1, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (2, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (4, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (4, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (5, 2, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (6, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (7, 3, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (8, 4, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000), (9, 5, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);

-- 集成测试曾留下的无业务角色不参与权限解析；幂等清理，避免污染角色列表。
DELETE FROM sys_role WHERE id = 6 AND code = 'it_role_213778866114400';

-- 细粒度权限只覆盖管理面；agent 业务接口的边界仍由登录态与资源归属校验保证。
CREATE TABLE IF NOT EXISTS sys_permission
(
    id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    code       VARCHAR(64)  NOT NULL COMMENT '权限码',
    name       VARCHAR(64)  NOT NULL COMMENT '权限名称',
    module     VARCHAR(32)  NOT NULL COMMENT '所属模块',
    created_at BIGINT       NOT NULL COMMENT '创建时刻（epoch millis）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sys_permission_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '权限点定义';

CREATE TABLE IF NOT EXISTS sys_role_permission
(
    role_id       BIGINT NOT NULL COMMENT '角色 id',
    permission_id BIGINT NOT NULL COMMENT '权限点 id',
    created_at    BIGINT NOT NULL COMMENT '关联建立时刻（epoch millis）',
    PRIMARY KEY (role_id, permission_id),
    KEY idx_sys_role_permission_permission (permission_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT '角色权限关联';

INSERT INTO sys_permission (id, code, name, module, created_at)
VALUES (1, 'sys:user:view', '查看用户', '用户管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (2, 'sys:user:create', '新增用户', '用户管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (3, 'sys:user:update', '编辑用户', '用户管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (4, 'sys:user:manage-status', '启用/禁用用户', '用户管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (5, 'sys:user:delete', '删除用户', '用户管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (6, 'sys:role:view', '查看角色', '角色管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (7, 'sys:role:manage-permission', '分配角色权限', '角色管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000),
       (8, 'sys:dept:view', '查看部门', '部门管理', UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000)
ON DUPLICATE KEY UPDATE name = VALUES(name), module = VALUES(module);

INSERT INTO sys_role_permission (role_id, permission_id, created_at)
SELECT 1, id, UNIX_TIMESTAMP(CURRENT_TIMESTAMP(3)) * 1000 FROM sys_permission
ON DUPLICATE KEY UPDATE created_at = VALUES(created_at);
