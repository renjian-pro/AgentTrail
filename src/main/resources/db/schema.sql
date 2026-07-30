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
