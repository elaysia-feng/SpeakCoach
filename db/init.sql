-- =============================================================================
-- SpeakCoach · 数据库初始化脚本 (v2 · 重设计版)
-- =============================================================================
-- 文件:        db/init.sql
-- 用途:        一键初始化 SpeakCoach 后端所需的全部 schema + 种子数据
-- 数据库:      MySQL 8.0+
-- 字符集:      utf8mb4 / utf8mb4_unicode_ci (支持完整中文 + emoji)
-- 存储引擎:    InnoDB (事务 + 外键)
-- 适用项目:    backend-java (Spring Boot + MyBatis-Plus)
--              backend-python (FastAPI + LangGraph)
-- 上次重构:    2026-06-07
-- 数据来源:    7 个 MyBatis-Plus 实体 + 7 个 Mapper + 12 个 Service + 5 个 DTO
--              + 10 个 Controller + Python Pydantic/Workflow + Frontend TS
--              + 4 个历史迁移文件 (add-persona / add-ability-history /
--              add-user-audio-and-speech-metrics / add-error-book)
--
-- ===== 用法 ==================================================================
-- 全新部署:    mysql -u root -p < init.sql
-- 幂等执行:    本脚本默认用 CREATE TABLE IF NOT EXISTS, 多次执行安全
-- 完整重置:    取消本文件第 0 节注释, 再次执行 (会清空所有数据!)
--
-- ===== 表清单 (7 张) =========================================================
--  user                  用户主表 (账号 / 凭证 / 偏好)
--  speaking_session      对话会话 (一轮口语练习 = 一个 session)
--  speaking_turn         会话内的单轮对话 (本系统最核心, 字段密度最大)
--  audit_log             LangGraph 节点审计日志 (回放 / 调试 / 评估)
--  user_ability_profile  用户能力画像 (1:1 with user, 4 维评分 + CEFR)
--  user_ability_history  用户能力历史快照 (append-only, 趋势图用)
--  user_error_book       错题本 (按 user 累计, 三元组去重)
--
-- ===== 关系图 ===============================================================
--                         ┌──────────────┐
--                         │     user     │ ◄── 所有表都从 user.id 级联
--                         └──────┬───────┘
--                ┌───────────────┼───────────────┬───────────────────┐
--                │               │               │                   │
--                ▼               ▼               ▼                   ▼
--       speaking_session  user_ability_    speaking_turn      user_ability_
--        (1:N per user)    profile          (1:N per session)  history
--                │         (1:1 per user)            │         (1:N per user)
--                │                                   │
--                └──────────► audit_log ◄────────────┘
--                            (N rows per turn)
--
--  user ──► user_error_book
--         (1:N per user, 错题累计)
--
-- ===== 注意事项 =============================================================
-- 1) db/migrations/ 下 4 个迁移文件是 init.sql 的历史副本, 全新部署不需要跑
-- 2) 所有 updated_at 列已加 ON UPDATE CURRENT_TIMESTAMP, 由 DB 自动维护
-- 3) 所有 created_at 列只设 DEFAULT, 不设 ON UPDATE, 创建后永不变
-- 4) 所有 created_at / createdAt 字段在 Java 实体均标记 FieldStrategy.NEVER,
--    强制让 DB DEFAULT 生效 (参见 AuditServiceImpl 注释 #29)
-- 5) 验证码 (auth.send-code / auth.verify-code) 走 Redis, 不在本 init.sql 中
-- 6) 若需启用软删除, 见文末"扩展说明"节
-- =============================================================================


-- ----------------------------------------------------------------------------
-- 0. 完整重置 (危险! 默认注释, 全新部署请取消下方 11 行注释)
-- ----------------------------------------------------------------------------
-- SET FOREIGN_KEY_CHECKS = 0;
-- DROP TABLE IF EXISTS user_ability_history;
-- DROP TABLE IF EXISTS audit_log;
-- DROP TABLE IF EXISTS speaking_turn;
-- DROP TABLE IF EXISTS user_ability_profile;
-- DROP TABLE IF EXISTS user_error_book;
-- DROP TABLE IF EXISTS speaking_session;
-- DROP TABLE IF EXISTS user;
-- SET FOREIGN_KEY_CHECKS = 1;


-- ----------------------------------------------------------------------------
-- 1. 创建数据库
-- ----------------------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS speakcoach
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE speakcoach;


-- ============================================================================
-- 表 1/7: user — 用户主表
-- ============================================================================
-- 用途:     账号 + 凭证 + 偏好. 系统所有其他表都通过 user.id 与本表关联.
-- 主键:     id (BIGINT 自增, 物理聚簇)
-- 唯一键:   username, email (注册查重 + 登录查询)
-- 关联:     1:N → speaking_session / speaking_turn / audit_log
--           1:1 → user_ability_profile
--           1:N → user_ability_history / user_error_book
-- 关联实体: com.speakcoach.entity.User
-- 数据生命周期: append (仅注册时新增, 无物理删除)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user (
    -- 主键
    id              BIGINT       NOT NULL AUTO_INCREMENT
                                    COMMENT '用户主键 (自增, 全局唯一)',

    -- 凭证
    username        VARCHAR(64)  NOT NULL
                                    COMMENT '登录用户名 (唯一, 注册时必填)',
    email           VARCHAR(255) NOT NULL
                                    COMMENT '登录邮箱 (唯一, 验证码登录用)',
    password_hash   VARCHAR(255) NOT NULL
                                    COMMENT 'BCrypt 加密后的密码哈希 (cost=10, 服务端生成)',

    -- 偏好 (M1-B 引入; 实体用 FieldStrategy.NEVER 阻止 Java 写入, 全靠 DB DEFAULT)
    coach_persona   VARCHAR(32)  NOT NULL DEFAULT 'warm_strict'
                                    COMMENT 'AI 教练人格 key: warm_strict / friendly_tutor / ielts_examiner / patient_grandma',
    preferred_voice VARCHAR(64)  NOT NULL DEFAULT 'linqian_voice'
                                    COMMENT 'TTS 音色 key (当前仅 linqian_voice, 预留扩展)',

    -- 时间戳
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    COMMENT '注册时间 (DB 自动维护, 不允许 Java 写入)',

    -- 主键约束
    PRIMARY KEY (id),

    -- 唯一约束: 防止同名 / 同邮箱重复注册
    UNIQUE KEY uk_user_username (username),
    UNIQUE KEY uk_user_email    (email)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='用户主表 — 账号 / 凭证 / 偏好';


-- ============================================================================
-- 表 2/7: speaking_session — 对话会话
-- ============================================================================
-- 用途:     一次完整口语练习 = 一个 session. 包含场景选择、回合计数、最终摘要.
-- 主键:     id (VARCHAR(64), 由 Java 端生成 UUID 风格字符串)
-- 关键索引: idx_session_user (user_id, created_at) — 会话列表按时间倒序
-- 关联:     N:1 → user
--           1:N → speaking_turn
--           1:N → audit_log
--           1:N → user_ability_history
-- 关联实体: com.speakcoach.entity.SpeakingSession
-- 数据生命周期: 创建后状态机推进 (active → finished), 通常不会物理删除
-- ============================================================================
CREATE TABLE IF NOT EXISTS speaking_session (
    -- 主键 (UUID, 应用层生成, 非自增)
    id              VARCHAR(64)  NOT NULL
                                    COMMENT '会话主键 (UUID 风格, 应用层生成)',

    -- 外键
    user_id         BIGINT       NOT NULL
                                    COMMENT '所属用户 → user.id (级联删除)',

    -- 业务字段
    scene           VARCHAR(32)  NOT NULL DEFAULT 'free'
                                    COMMENT '练习场景: interview / travel / daily_chat / business (受 Service 白名单校验)',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active'
                                    COMMENT '会话状态: active / finished',
    turn_count      INT          NOT NULL DEFAULT 0
                                    COMMENT '已完成的轮次数 (服务层 selectForUpdate 自增, 防止并发冲号)',

    -- 摘要 (JSON, 会话结束后由 LLM 总结写入)
    summary         JSON         NULL
                                    COMMENT '会话最终摘要 (JSON: total_turns / error_counts / ability_delta / next_focus / highlights)',

    -- 时间戳
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                    COMMENT '会话创建时间 (DB 自动维护)',
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                              ON UPDATE CURRENT_TIMESTAMP
                                    COMMENT '最近修改时间 (DB 自动维护, 任意 UPDATE 都会刷新)',

    -- 主键
    PRIMARY KEY (id),

    -- 外键
    CONSTRAINT fk_session_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,

    -- 二级索引: 会话列表按时间倒序
    KEY idx_session_user (user_id, created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='口语练习会话 — 一轮完整对话 = 一个 session';


-- ============================================================================
-- 表 3/7: speaking_turn — 会话内的单轮对话 (核心表, 字段密度最大)
-- ============================================================================
-- 用途:     记录每一轮用户输入 + AI 回复 + 评分 + 语音指标.
--           是系统最核心的表, 包含 5 个 JSON 列 (corrections / ability_score /
--           speech_metrics / word_timestamps) 和 1 个 LangGraph checkpoint_id.
-- 主键:     id (BIGINT 自增)
-- 关键唯一: uk_turn_session_turn (session_id, turn_id) — 同会话内 turn_id 唯一
-- 关联:     N:1 → user
--           N:1 → speaking_session
-- 关联实体: com.speakcoach.entity.SpeakingTurn
-- 数据生命周期: append-only (创建后不修改, 不物理删除)
-- ============================================================================
CREATE TABLE IF NOT EXISTS speaking_turn (
    -- 主键
    id                  BIGINT       NOT NULL AUTO_INCREMENT
                                        COMMENT '轮次主键 (自增)',

    -- 外键
    user_id             BIGINT       NOT NULL
                                        COMMENT '所属用户 → user.id',
    session_id          VARCHAR(64)  NOT NULL
                                        COMMENT '所属会话 → speaking_session.id',

    -- 业务字段
    turn_id             INT          NOT NULL
                                        COMMENT '会话内的轮次序号 (从 1 开始, 同一 session 内唯一)',
    user_text           TEXT         NULL
                                        COMMENT '用户输入文本 (WhisperX 转写后或直接输入)',
    user_audio_url      VARCHAR(255) NULL
                                        COMMENT '用户本轮录音的可回放 URL (OSS 路径)',
    ai_reply            TEXT         NULL
                                        COMMENT 'AI 回复文本',
    audio_url           VARCHAR(255) NULL
                                        COMMENT 'AI 回复的 TTS 音频 URL (由 Python GPT-SoVITS 生成)',

    -- JSON 字段 (以 MySQL 原生 JSON 类型存储, 服务层以 String 形式读写)
    corrections         JSON         NULL
                                        COMMENT '本轮语法纠错数组 (JSON 列表)',
    shadow_answer       TEXT         NULL
                                        COMMENT '影子跟读示范答案 (可选)',
    ability_score       JSON         NULL
                                        COMMENT '能力评分快照: {grammar, vocabulary, fluency, logic, pronunciation}',
    speech_metrics      JSON         NULL
                                        COMMENT '语音侧指标: 语速 / 停顿 / 完整度 / 跟读相似度 等',
    word_timestamps     JSON         NULL
                                        COMMENT 'WhisperX 单词级时间戳数组: [{word, start, end}, ...]',

    -- 策略 & 状态
    strategy            VARCHAR(32)  NULL
                                        COMMENT 'LangGraph 选定的策略: normal_follow_up / hint_question / challenge_question / review_old_error',
    checkpoint_id       VARCHAR(128) NULL
                                        COMMENT 'LangGraph checkpoint 标识 (用于跨轮状态恢复, 当前恒为 NULL)',
    status              VARCHAR(20)  NOT NULL DEFAULT 'active'
                                        COMMENT '轮次生命周期: active (当前唯一写入值, 预留 finished / failed)',

    -- 时间戳
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        COMMENT '轮次写入时间 (DB 自动维护)',

    -- 主键
    PRIMARY KEY (id),

    -- 外键
    CONSTRAINT fk_turn_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,
    CONSTRAINT fk_turn_session
        FOREIGN KEY (session_id) REFERENCES speaking_session (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,

    -- 唯一约束: 同会话内 turn_id 不重复 (由会话服务层的 selectForUpdate 串行化保证)
    UNIQUE KEY uk_turn_session_turn (session_id, turn_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='会话内的单轮对话 — 核心事实表, 包含 5 个 JSON 列';


-- ============================================================================
-- 表 4/7: audit_log — LangGraph 节点审计日志
-- ============================================================================
-- 用途:     记录 LangGraph 每个节点 (grammar_check / shadow / strategy_router ...)
--           的输入 / 输出 / 耗时 / 模型, 用于回放 / 调试 / 离线评估.
-- 主键:     id (BIGINT 自增)
-- 关联:     N:1 → user (可空: 系统级审计可不带用户)
--           N:1 → speaking_session (可空: 跨会话审计)
-- 关联实体: com.speakcoach.entity.AuditLog
-- 数据生命周期: append-only (永不修改, 永不删除)
-- ============================================================================
CREATE TABLE IF NOT EXISTS audit_log (
    -- 主键
    id                  BIGINT       NOT NULL AUTO_INCREMENT
                                        COMMENT '审计主键 (自增)',

    -- 外键 (可空: 部分审计为系统级, 不归属用户/会话)
    user_id             BIGINT       NULL
                                        COMMENT '所属用户 → user.id (可空: 系统级审计)',
    session_id          VARCHAR(64)  NULL
                                        COMMENT '所属会话 → speaking_session.id (可空: 跨会话审计)',
    turn_id             INT          NULL
                                        COMMENT '所属轮次 (在会话内的序号, 可空)',

    -- 节点信息
    node_name           VARCHAR(64)  NOT NULL
                                        COMMENT 'LangGraph 节点名: grammar_check / shadow_answer / strategy_router / ability_score / summary / tts / ...',

    -- 输入 / 输出 (JSON)
    input_json          JSON         NULL
                                        COMMENT '节点输入的 JSON 序列化 (完整快照)',
    output_json         JSON         NULL
                                        COMMENT '节点输出的 JSON 序列化 (完整快照)',

    -- 性能 / 模型
    latency_ms          INT          NULL
                                        COMMENT '节点耗时 (毫秒)',
    model_name          VARCHAR(64)  NULL
                                        COMMENT '调用的大模型名 (e.g. gpt-4o / claude-3-5 / stub)',
    prompt_version      VARCHAR(32)  NULL
                                        COMMENT 'prompt 模板版本 (如 v1.2.0, 配合 prompts/ 目录的版本管理)',

    -- 时间戳
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        COMMENT '审计写入时间 (DB 自动维护)',

    -- 主键
    PRIMARY KEY (id),

    -- 外键
    CONSTRAINT fk_audit_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,
    CONSTRAINT fk_audit_session
        FOREIGN KEY (session_id) REFERENCES speaking_session (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,

    -- 二级索引: 按会话+轮次反查, 按节点+时间做时间序列分析
    KEY idx_audit_session_turn (session_id, turn_id),
    KEY idx_audit_node_created (node_name, created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='LangGraph 节点审计日志 — 回放 / 调试 / 离线评估';


-- ============================================================================
-- 表 5/7: user_ability_profile — 用户能力画像 (1:1)
-- ============================================================================
-- 用途:     维护用户当前的能力画像: 4 维评分 (语法/词汇/流利度/逻辑) + 常见错误
--           + CEFR 等级 + 连续打卡天数. M2-F 写 CEFR, M2-E 维护 streak.
-- 主键:     user_id (直接复用 user.id, 1:1 关系, 无独立自增主键)
-- 关联:     1:1 → user
-- 关联实体: com.speakcoach.entity.UserAbilityProfile
-- 数据生命周期: read-mostly (读多写少, 每次 turn 后由 Service 增量更新)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_ability_profile (
    -- 主键 (= user.id, 无独立自增)
    user_id             BIGINT       NOT NULL
                                        COMMENT '用户主键 (1:1 复用 user.id, 同时也是外键)',

    -- 4 维评分
    grammar_score       INT          NOT NULL DEFAULT 0
                                        COMMENT '语法分 0-100, 默认值兜底',
    vocabulary_score    INT          NOT NULL DEFAULT 0
                                        COMMENT '词汇分 0-100, 默认值兜底',
    fluency_score       INT          NOT NULL DEFAULT 0
                                        COMMENT '流利度分 0-100, 默认值兜底',
    logic_score         INT          NOT NULL DEFAULT 0
                                        COMMENT '逻辑分 0-100, 默认值兜底',

    -- 常见错误 (JSON)
    common_errors       JSON         NULL
                                        COMMENT '用户高频错误模式: [{pattern, count, examples}, ...]',

    -- CEFR & 评估 (M1-A 占位, M2-F 才会写)
    cefr_level          VARCHAR(8)   NULL
                                        COMMENT 'CEFR 等级 A1-C2, 由 M2-F 写入',
    last_assessed_at    DATETIME     NULL
                                        COMMENT '最近一次 CEFR 评估时间 (M2-F 写入)',

    -- 连续打卡 (M2-E 维护)
    streak_count        INT          NOT NULL DEFAULT 0
                                        COMMENT '连续打卡天数 (M2-E 写入)',

    -- 时间戳
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                              ON UPDATE CURRENT_TIMESTAMP
                                        COMMENT '画像最近更新时间 (DB 自动维护)',

    -- 主键
    PRIMARY KEY (user_id),

    -- 外键 (同时是主键, 1:1 关系)
    CONSTRAINT fk_ability_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='用户能力画像 — 4 维评分 + CEFR + 打卡 (1:1 with user)';


-- ============================================================================
-- 表 6/7: user_ability_history — 用户能力历史快照 (append-only)
-- ============================================================================
-- 用途:     每次 turn 结束后, 对 user_ability_profile 做一次完整 JSON 快照.
--           用于 Profile 页趋势图 + strategy_router 的"长期记忆"参考.
-- 主键:     id (BIGINT 自增)
-- 关联:     N:1 → user
--           N:1 → speaking_session (SET NULL: 即使会话被删, 历史保留)
-- 关联实体: com.speakcoach.entity.UserAbilityHistory
-- 数据生命周期: append-only (永不修改, 永不删除)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_ability_history (
    -- 主键
    id                  BIGINT       NOT NULL AUTO_INCREMENT
                                        COMMENT '快照主键 (自增)',

    -- 外键
    user_id             BIGINT       NOT NULL
                                        COMMENT '所属用户 → user.id (级联删除)',
    session_id          VARCHAR(64)  NULL
                                        COMMENT '触发快照的会话 → speaking_session.id (SET NULL: 会话删了历史保留)',
    turn_id             INT          NULL
                                        COMMENT '触发快照的轮次 (在会话内的序号, 可空: 纯画像更新时为 NULL)',

    -- 快照
    snapshot_json       JSON         NOT NULL
                                        COMMENT '当时 user_ability_profile 的完整 JSON 快照 (4 维分 + common_errors + cefr_level)',

    -- 时间戳
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        COMMENT '快照写入时间 (DB 自动维护)',

    -- 主键
    PRIMARY KEY (id),

    -- 外键
    CONSTRAINT fk_ability_history_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,
    CONSTRAINT fk_ability_history_session
        FOREIGN KEY (session_id) REFERENCES speaking_session (id)
        ON DELETE SET NULL  ON UPDATE CASCADE,

    -- 二级索引
    KEY idx_ability_history_user_created (user_id, created_at),
    KEY idx_ability_history_user_session (user_id, session_id)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='用户能力历史快照 — 按 turn 写入, append-only';


-- ============================================================================
-- 表 7/7: user_error_book — 错题本
-- ============================================================================
-- 用途:     累计用户说错的句子 + AI 纠正 + 掌握度. 配合间隔重复 (mastery_level)
--           在 review 页推题.
-- 主键:     id (BIGINT 自增)
-- 关键唯一: uk_error_triple (user_id, original, corrected) — 三元组去重
-- 关联:     N:1 → user
-- 关联实体: com.speakcoach.entity.UserErrorBook
-- 数据生命周期: insert + 偶尔 update (markMastered 改 mastery_level)
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_error_book (
    -- 主键
    id                  BIGINT       NOT NULL AUTO_INCREMENT
                                        COMMENT '错题主键 (自增)',

    -- 外键
    user_id             BIGINT       NOT NULL
                                        COMMENT '所属用户 → user.id (级联删除, 错题本按用户隔离)',

    -- 类型
    type                VARCHAR(16)  NOT NULL DEFAULT 'grammar'
                                        COMMENT '错题类型: grammar / vocab / fluency / logic (受 Service 白名单校验)',

    -- 错题主体
    -- 注意: original / corrected 用 VARCHAR(255) 而非 TEXT.
    --   它们是 UNIQUE KEY uk_error_triple 的组成部分, MySQL 不允许 TEXT/BLOB
    --   无前缀地进入索引 (error 1170), 且 utf8mb4 下索引总长不能超 3072 字节
    --   (error 1071). 三元组上限: user_id 8B + original 1020B + corrected 1020B
    --   = 2048 字节, 留出充足余量. 255 字符足够覆盖 99% 错句.
    --   explanation 保持 TEXT, 因它不在索引里.
    original            VARCHAR(255) NOT NULL
                                        COMMENT '用户说错的原句 (三元组去重键之一, VARCHAR(255) 避开 TEXT/TEXT 索引限制)',
    corrected           VARCHAR(255) NOT NULL
                                        COMMENT 'AI 给出的正确版本 (三元组去重键之一, VARCHAR(255) 避开 TEXT 索引限制)',
    explanation         TEXT         NULL
                                        COMMENT '可选的纠错解释 (来自 grammar_check 节点, 不进索引, 可用 TEXT)',

    -- 来源追溯
    source_session_id   VARCHAR(64)  NULL
                                        COMMENT '外键 → speaking_session.id (可空: 批量导入或回填场景)',
    source_turn_id      INT          NULL
                                        COMMENT '会话内对应的 turn_id (可空)',

    -- 间隔重复 (Spaced Repetition)
    mastery_level       TINYINT      NOT NULL DEFAULT 0
                                        COMMENT '掌握度 0=new / 1=learning / 2=reviewing / 3=mastered',
    next_review_at      DATETIME     NULL
                                        COMMENT '下一次推荐复习时间; mastered = now + 7 days',
    review_count        INT          NOT NULL DEFAULT 0
                                        COMMENT '已复习次数; markMastered 时 +1',

    -- 时间戳
    created_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                        COMMENT '首次进入错题本的时间 (DB 自动维护)',
    updated_at          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                                              ON UPDATE CURRENT_TIMESTAMP
                                        COMMENT '最近一次修改时间 (markMastered 时刷新, DB 自动维护)',

    -- 主键
    PRIMARY KEY (id),

    -- 外键
    CONSTRAINT fk_error_user
        FOREIGN KEY (user_id) REFERENCES user (id)
        ON DELETE CASCADE  ON UPDATE CASCADE,

    -- 唯一约束: (user, 错句, 纠正) 三元组去重, 应用层用 DuplicateKeyException 吞错
    UNIQUE KEY uk_error_triple (user_id, original, corrected),

    -- 二级索引
    KEY idx_error_review     (user_id, mastery_level, next_review_at),
    KEY idx_error_user_created (user_id, created_at)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
  COMMENT='错题本 — 按 (user, 错句, 纠正) 三元组去重累计';


-- ============================================================================
-- 8. 种子数据 (幂等, 可重复执行)
-- ============================================================================
-- 注入一个 demo 账号, 密码明文 = "password".
-- 哈希值: BCrypt cost=10, 由 jBCrypt 生成.
-- 用途: 方便本地联调, 部署到生产前请删除本节.
-- ============================================================================
INSERT INTO user (username, email, password_hash)
VALUES (
    'demo',
    'demo@qq.com',
    '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy'
)
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    email         = VALUES(email);


-- ============================================================================
-- 9. 验证脚本 (取消下方注释可逐条运行)
-- ============================================================================
-- USE speakcoach;
--
-- -- 9.1 列出所有表
-- SHOW TABLES;
--
-- -- 9.2 查看 user 表完整结构
-- SHOW CREATE TABLE user\G
--
-- -- 9.3 统计各表行数
-- SELECT 'user'                     AS tbl, COUNT(*) AS n FROM user
-- UNION ALL SELECT 'speaking_session',     COUNT(*) FROM speaking_session
-- UNION ALL SELECT 'speaking_turn',        COUNT(*) FROM speaking_turn
-- UNION ALL SELECT 'audit_log',            COUNT(*) FROM audit_log
-- UNION ALL SELECT 'user_ability_profile', COUNT(*) FROM user_ability_profile
-- UNION ALL SELECT 'user_ability_history', COUNT(*) FROM user_ability_history
-- UNION ALL SELECT 'user_error_book',      COUNT(*) FROM user_error_book;
--
-- -- 9.4 验证 demo 账号
-- SELECT id, username, email, coach_persona, preferred_voice, created_at
-- FROM user WHERE username = 'demo';
--
-- -- 9.5 验证外键级联关系
-- SELECT
--     TABLE_NAME, CONSTRAINT_NAME, REFERENCED_TABLE_NAME, DELETE_RULE
-- FROM information_schema.REFERENTIAL_CONSTRAINTS
-- WHERE CONSTRAINT_SCHEMA = 'speakcoach'
-- ORDER BY TABLE_NAME, CONSTRAINT_NAME;


-- ============================================================================
-- 10. 扩展说明 (按需启用)
-- ============================================================================
--
-- ----- 启用软删除 -----------------------------------------------------
-- 当前 application.yml 配置了 mybatis-plus.global-config.db-config.logic-delete-field
-- = deleted, 但 7 个实体均未声明 @TableLogic 字段, 该配置处于"未激活"状态.
-- 若要启用软删除, 给所有表添加 deleted 列 (示例):
--
--   ALTER TABLE user                  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE speaking_session      ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE speaking_turn         ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE audit_log             ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE user_ability_profile  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE user_ability_history  ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--   ALTER TABLE user_error_book       ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0;
--
-- 然后在每个实体加 @TableLogic private Integer deleted; 字段, 即可生效.
--
-- ----- 接入 Flyway / Liquibase -----------------------------------------
-- 当前 Spring Boot 项目未配置任何 migration 工具. 建议二选一:
--
--   A) 单源 = init.sql (本文件):
--      删除 db/migrations/ 目录, 文档写明 init.sql 是在线演进的, 每次发版人工重建.
--
--   B) 单源 = migrations:
--      把本 init.sql 拆成 V1__init.sql ~ V7__error_book.sql, 加 flyway-mysql 依赖,
--      application.yml 加:
--        spring.flyway.enabled: true
--        spring.flyway.locations: filesystem:../db/migration
--
-- ----- 验证码存储 -----------------------------------------------------
-- auth.send-code / auth.verify-code 走 Redis (StringRedisTemplate),
-- key 为 verification:<email> 和 verification:cooldown:<email>, 不进 MySQL.
-- 若未来需要审计或重发, 可独立建一张 verification_code 表 (本脚本未含).
--
-- ----- practice_mode / practice_target 列 -----------------------------
-- 当前作为 @RequestParam 进入 ChatController, 最终落到 speaking_turn.speech_metrics
-- JSON 内. 如果未来要按练习模式做查询/筛选, 可升格为独立列:
--
--   ALTER TABLE speaking_turn
--     ADD COLUMN practice_mode   VARCHAR(32) NULL COMMENT '练习模式: free / shadowing / correction',
--     ADD COLUMN practice_target VARCHAR(255) NULL COMMENT '练习目标句子 (shadowing 时使用)';
-- =============================================================================
