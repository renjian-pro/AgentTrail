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
