-- ============================================================================
-- V6：把「同一角色在同一 BOSS 的同一难度下只能排一趟」下沉到数据库
--
-- 背景（本次评审 M2）：
--   这条规则之前**只**存在于前端巡检脚本 scripts-check-integrity.mjs 里。
--   服务端允许 moveMember 跨 BOSS / 跨难度自由调车，没有任何一处校验，
--   于是「同一个角色同时出现在卡琳 N 的第 1 趟和第 2 趟」可以被直接写入。
--   写进去以后，车次一览表和「我的车次」都看不出问题，只有事后巡检才会发现。
--
-- 为什么要单独一张影子表：
--   run_member 只知道 (run_id, character_id)，boss_id / tier 在 boss_run 上。
--   PostgreSQL 的唯一索引不能跨表，所以用 run_seat 冗余保存
--   (boss_id, tier, character_id)，由触发器随 run_member 同步维护，再对它加唯一约束。
--
-- 为什么要先归档再建约束：
--   CODE_REVIEW 里记录过 2 条历史数据本身就违反这条规则
--   （上周塞伦 H 的 runId=1/2、本周卡尔洛斯 N 的 runId=67/68）。
--   如果直接建唯一索引，Flyway 会失败、应用起不来。
--   这里先把「要放弃的那条」完整归档到 run_seat_conflict_archive，
--   再删除对应席位，最后才建唯一索引 —— 数据可追溯、可人工恢复。
--
-- 保留哪一条：人数多的那趟（那才是实际在打的车），人数相同则保留 run_id 小的。
-- 这与运维直觉一致，且不会把「实际在打的那趟」删掉。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 影子表：一行 = 某角色占了某 BOSS 某难度的某趟车
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS run_seat (
    id            bigserial PRIMARY KEY,
    run_member_id bigint      NOT NULL UNIQUE,   -- 与 run_member 一对一
    boss_id       bigint      NOT NULL,
    tier          varchar(8)  NOT NULL,
    character_id  bigint      NOT NULL,
    run_id        bigint      NOT NULL,
    CONSTRAINT fk_run_seat_member FOREIGN KEY (run_member_id)
        REFERENCES run_member (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_run_seat_character ON run_seat (character_id);

-- ---------------------------------------------------------------------------
-- 2) 归档表：万一需要人工恢复，原始席位信息在这里
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS run_seat_conflict_archive (
    id            bigserial PRIMARY KEY,
    archived_at   timestamptz NOT NULL DEFAULT now(),
    run_member_id bigint      NOT NULL,
    run_id        bigint      NOT NULL,
    boss_id       bigint      NOT NULL,
    tier          varchar(8)  NOT NULL,
    character_id  bigint      NOT NULL,
    player_id     bigint,
    slot_no       integer,
    reason        text
);

-- ---------------------------------------------------------------------------
-- 3) 回填 + 去重（按 (boss_id, tier, character_id) 分组，只保留一趟）
--    注意：这一步要在建唯一索引之前做，否则索引本身就建不起来。
-- ---------------------------------------------------------------------------
WITH ranked AS (
  SELECT m.id                AS run_member_id,
         m.run_id,
         r.boss_id,
         r.tier,
         m.character_id,
         c.player_id,
         m.slot_no,
         row_number() OVER (
             PARTITION BY r.boss_id, r.tier, m.character_id
             ORDER BY (SELECT count(*) FROM run_member x WHERE x.run_id = m.run_id) DESC,
                      m.run_id,
                      m.id
         ) AS rn
    FROM run_member m
    JOIN boss_run r  ON r.id = m.run_id
    JOIN "character" c ON c.id = m.character_id
)
INSERT INTO run_seat_conflict_archive
       (run_member_id, run_id, boss_id, tier, character_id, player_id, slot_no, reason)
SELECT run_member_id, run_id, boss_id, tier, character_id, player_id, slot_no,
       '同一角色在同一 BOSS 同一难度下出现多趟，V6 自动保留人数最多的一趟（规范来源：CODE_REVIEW 第三节规则 4）'
  FROM ranked
 WHERE rn > 1;

-- 先删掉被判定为重复的席位（归档表里已有完整备份）
DELETE FROM run_member m
 WHERE m.id IN (
   SELECT ranked.run_member_id
     FROM (
       SELECT m2.id AS run_member_id,
              row_number() OVER (
                  PARTITION BY r2.boss_id, r2.tier, m2.character_id
                  ORDER BY (SELECT count(*) FROM run_member x WHERE x.run_id = m2.run_id) DESC,
                           m2.run_id,
                           m2.id
              ) AS rn
         FROM run_member m2
         JOIN boss_run r2 ON r2.id = m2.run_id
     ) ranked
    WHERE ranked.rn > 1
 );

-- 回填影子表
INSERT INTO run_seat (run_member_id, boss_id, tier, character_id, run_id)
SELECT m.id, r.boss_id, r.tier, m.character_id, m.run_id
  FROM run_member m
  JOIN boss_run r ON r.id = m.run_id
ON CONFLICT (run_member_id) DO NOTHING;

-- 建核心约束：同一 BOSS + 同一难度 + 同一角色，只能有一趟
CREATE UNIQUE INDEX IF NOT EXISTS uq_run_seat_boss_tier_character
    ON run_seat (boss_id, tier, character_id);

-- ---------------------------------------------------------------------------
-- 4) 同步触发器：run_member 增删改时维护影子表
--    用 AFTER：BEFORE INSERT 时 run_member 那一行还不存在，
--    往 run_seat 插会撞外键。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_run_seat() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_boss_id bigint;
  v_tier    varchar(8);
BEGIN
  IF TG_OP = 'DELETE' THEN
    DELETE FROM run_seat WHERE run_member_id = OLD.id;
    RETURN OLD;
  END IF;

  -- INSERT / UPDATE：先清掉旧占用，再按新值登记。
  -- 换位 / 调车这类「先破坏再重建」的操作也能正确反映到影子表。
  DELETE FROM run_seat WHERE run_member_id = NEW.id;

  SELECT r.boss_id, r.tier INTO v_boss_id, v_tier
    FROM boss_run r WHERE r.id = NEW.run_id;
  IF v_boss_id IS NULL THEN
    RETURN NEW;   -- 车次不存在：交给外键报错
  END IF;

  -- 与 V5 的加锁顺序保持一致：先角色，后 BOSS
  PERFORM gmsr_lock_character(NEW.character_id);
  PERFORM gmsr_lock_bosses(v_boss_id);

  INSERT INTO run_seat (run_member_id, boss_id, tier, character_id, run_id)
  VALUES (NEW.id, v_boss_id, v_tier, NEW.character_id, NEW.run_id);

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_run_seat ON run_member;
CREATE TRIGGER trg_sync_run_seat
    AFTER INSERT OR UPDATE ON run_member
    FOR EACH ROW EXECUTE FUNCTION sync_run_seat();

-- 删除由 run_seat 上的 ON DELETE CASCADE 负责，不需要额外触发器

-- ---------------------------------------------------------------------------
-- 5) 车次改绑 BOSS / 改难度时，影子表跟着改
--    否则会一直把旧 BOSS 的占用算在头上，导致合法操作被误拒。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION sync_run_seat_of_run() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.boss_id IS NOT DISTINCT FROM OLD.boss_id
     AND NEW.tier IS NOT DISTINCT FROM OLD.tier THEN
    RETURN NEW;
  END IF;

  PERFORM gmsr_lock_bosses(OLD.boss_id, NEW.boss_id);

  UPDATE run_seat
     SET boss_id = NEW.boss_id, tier = NEW.tier
   WHERE run_id = NEW.id;

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_sync_run_seat_of_run ON boss_run;
CREATE TRIGGER trg_sync_run_seat_of_run
    AFTER UPDATE OF boss_id, tier ON boss_run
    FOR EACH ROW EXECUTE FUNCTION sync_run_seat_of_run();
