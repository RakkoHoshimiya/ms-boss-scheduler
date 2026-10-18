-- GMSR BOSS 组队排班系统 —— 表结构参考（生产库由 Hibernate 按实体自动创建/更新）
-- 库：gmsr  模式：gmsr  账号：gmsr_app
-- 实际结构可用 \d+ gmsr.<表名> 查看

CREATE SCHEMA IF NOT EXISTS gmsr;

-- 系统账号（登录用）
CREATE TABLE IF NOT EXISTS gmsr.app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL UNIQUE,
    password_hash VARCHAR(120) NOT NULL,
    display_name  VARCHAR(64)  NOT NULL,
    role          VARCHAR(16)  NOT NULL,          -- ADMIN / EDITOR / VIEWER
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    -- boss排班 权限：管理员天然拥有，群友账号默认开启
    can_schedule  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL,
    updated_at    TIMESTAMPTZ  NOT NULL
);

-- 已有数据库升级时（Hibernate 无法给非空表加 NOT NULL 列，需手动执行）：
--   ALTER TABLE gmsr.app_user ADD COLUMN IF NOT EXISTS can_schedule boolean NOT NULL DEFAULT true;
--   ALTER TABLE gmsr.boss ADD COLUMN IF NOT EXISTS tiers varchar(64);
-- BOSS 分组（原初 / 神秘河 …）
CREATE TABLE IF NOT EXISTS gmsr.boss_group (
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(64) NOT NULL UNIQUE,
    sort_order INT         NOT NULL,
    note       VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- BOSS（含默认难度与每车人数上限）
CREATE TABLE IF NOT EXISTS gmsr.boss (
    id         BIGSERIAL PRIMARY KEY,
    group_id   BIGINT      NOT NULL REFERENCES gmsr.boss_group (id),
    name       VARCHAR(64) NOT NULL,
    tier       VARCHAR(8)  NOT NULL,              -- E=Easy / N / H / C / EX=Extreme / NONE
    capacity   INT         NOT NULL DEFAULT 6,
    sort_order INT         NOT NULL,
    enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    note       VARCHAR(255),
    -- 这个 BOSS 实际存在的难度，逗号分隔（如 N,H,EX）
    tiers      VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 群友
CREATE TABLE IF NOT EXISTS gmsr.player (
    id         BIGSERIAL PRIMARY KEY,
    nickname   VARCHAR(64) NOT NULL UNIQUE,
    remark     VARCHAR(255),
    active     BOOLEAN     NOT NULL DEFAULT TRUE,
    sort_order INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 角色（职业 + 战力）
CREATE TABLE IF NOT EXISTS gmsr.character (
    id         BIGSERIAL PRIMARY KEY,
    player_id  BIGINT      NOT NULL REFERENCES gmsr.player (id),
    job        VARCHAR(32) NOT NULL,
    power      INT         NOT NULL DEFAULT 0,    -- 单位：万
    main_char  BOOLEAN     NOT NULL DEFAULT FALSE,
    note       VARCHAR(255),
    sort_order INT         NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 车次
CREATE TABLE IF NOT EXISTS gmsr.boss_run (
    id         BIGSERIAL PRIMARY KEY,
    boss_id    BIGINT      NOT NULL REFERENCES gmsr.boss (id),
    tier       VARCHAR(8)  NOT NULL,
    run_index  INT         NOT NULL DEFAULT 1,
    scheduled  VARCHAR(32),
    status     VARCHAR(16) NOT NULL DEFAULT 'PLANNED',  -- PLANNED / DONE / CANCELED
    note       VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- 席位（谁上了哪趟车）
CREATE TABLE IF NOT EXISTS gmsr.run_member (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT      NOT NULL REFERENCES gmsr.boss_run (id),
    character_id BIGINT      NOT NULL REFERENCES gmsr.character (id),
    slot_no      INT         NOT NULL DEFAULT 1,
    role         VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    status       VARCHAR(16) NOT NULL DEFAULT 'CONFIRMED', -- CONFIRMED / TENTATIVE / ABSENT
    note         VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_run_character UNIQUE (run_id, character_id)
);
