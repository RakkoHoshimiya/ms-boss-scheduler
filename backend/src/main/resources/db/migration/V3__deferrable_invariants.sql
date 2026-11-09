-- ============================================================================
-- V3：让 V2 的不变量校验「事务最终状态」，并补上两条遗漏的写入路径
--
-- 背景（第四轮评审 P1）：
--   换位是把两个席位分别改到对方车次/席位。数据库执行第一条 UPDATE 时，
--   另一名成员还占着目标位置，于是：
--     · uq_run_slot 唯一索引先报「座位号重复」
--     · 满员车的容量检查先报「已满」
--   即使交换后的最终阵容完全合法，也会在中间状态被拒绝 —— 直接把换位功能打坏。
--
-- 解决思路：**把校验推迟到事务提交时**。
--   PostgreSQL 支持 DEFERRABLE 约束和 CONSTRAINT TRIGGER：
--   事务中途允许出现「暂时不合法」的中间态，COMMIT 时再统一校验最终状态。
--   这样换位、重排座位号这类「必须先破坏再重建」的操作就能正常完成，
--   而真正非法的最终状态依然会被拒绝。
--
-- 同时补两个之前没被覆盖的入口（评审指出）：
--   · boss_run 改绑到别的 BOSS（容量可能更小）→ 新触发器检查容量
--   · character 改属主玩家（可能与目标玩家在同一车次重复）→ 新触发器检查同人单角色
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 换掉 V2 的即时触发器与即时唯一索引
-- ---------------------------------------------------------------------------
DROP TRIGGER IF EXISTS trg_run_member_invariant ON run_member;
DROP INDEX IF EXISTS uq_run_slot;

-- ---------------------------------------------------------------------------
-- 2) 席位不变量：改为「提交时校验」
--    AFTER 触发器里这一行已经写入表中，所以计数时要按 id 排除自己。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_run_member_invariant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_capacity   integer;
  v_members    integer;
  v_player_id  bigint;
  v_dup        integer;
BEGIN
  SELECT b.capacity INTO v_capacity
    FROM boss_run r
    JOIN boss b ON b.id = r.boss_id
   WHERE r.id = NEW.run_id;
  IF v_capacity IS NULL THEN
    RETURN NULL;  -- 车次不存在：交给外键去报错
  END IF;

  -- 1) 容量：排除自己
  SELECT count(*) INTO v_members
    FROM run_member
   WHERE run_id = NEW.run_id
     AND id <> NEW.id;
  IF v_members >= v_capacity THEN
    RAISE EXCEPTION '这趟车已满 % 人，不能再加席位', v_capacity
      USING ERRCODE = 'check_violation',
            HINT = '请先在页面上移除成员，或调大该 BOSS 的容量';
  END IF;

  -- 2) 同一群友单角色（同样排除自己）
  SELECT c.player_id INTO v_player_id
    FROM "character" c WHERE c.id = NEW.character_id;
  IF v_player_id IS NOT NULL THEN
    SELECT count(*) INTO v_dup
      FROM run_member m
      JOIN "character" c ON c.id = m.character_id
     WHERE m.run_id = NEW.run_id
       AND m.id <> NEW.id
       AND c.player_id = v_player_id;
    IF v_dup > 0 THEN
      RAISE EXCEPTION '同一趟车里一个群友只能上一个角色'
        USING ERRCODE = 'check_violation',
              HINT = '该群友已经有别的角色在这趟车上';
    END IF;
  END IF;

  RETURN NULL;  -- AFTER 触发器的返回值会被忽略
END;
$$;

CREATE CONSTRAINT TRIGGER trg_run_member_invariant
    AFTER INSERT OR UPDATE ON run_member
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_run_member_invariant();

-- 座位号唯一也改成可延迟：换位时两条记录会短暂占用同一个 (run_id, slot_no)
ALTER TABLE run_member
    ADD CONSTRAINT uq_run_slot UNIQUE (run_id, slot_no) DEFERRABLE INITIALLY DEFERRED;

-- ---------------------------------------------------------------------------
-- 3) 补漏：车次改绑到另一个 BOSS 时，新 BOSS 的容量必须装得下现有人数
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_run_rebind_capacity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_capacity integer;
  v_members  integer;
BEGIN
  IF NEW.boss_id IS NOT DISTINCT FROM OLD.boss_id THEN
    RETURN NEW;
  END IF;

  SELECT capacity INTO v_capacity FROM boss WHERE id = NEW.boss_id;
  IF v_capacity IS NULL THEN
    RETURN NEW;  -- 交给外键
  END IF;

  SELECT count(*) INTO v_members FROM run_member WHERE run_id = NEW.id;
  IF v_members > v_capacity THEN
    RAISE EXCEPTION '无法把这趟车改到容量 % 的 BOSS：车上有 % 个席位', v_capacity, v_members
      USING ERRCODE = 'check_violation',
            HINT = '先移除多余席位，或换一个容量足够的 BOSS';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_run_rebind_capacity
    BEFORE UPDATE OF boss_id ON boss_run
    FOR EACH ROW EXECUTE FUNCTION check_run_rebind_capacity();

-- ---------------------------------------------------------------------------
-- 4) 补漏：角色改属主玩家时，不能与其所在车次里的同人角色重复
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_character_owner_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_run bigint;
BEGIN
  IF NEW.player_id IS NOT DISTINCT FROM OLD.player_id THEN
    RETURN NEW;
  END IF;

  SELECT m.run_id INTO v_run
    FROM run_member m
    JOIN "character" c2 ON c2.id = m.character_id
   WHERE m.character_id <> NEW.id
     AND c2.player_id = NEW.player_id
     AND m.run_id IN (SELECT run_id FROM run_member WHERE character_id = NEW.id)
   LIMIT 1;

  IF v_run IS NOT NULL THEN
    RAISE EXCEPTION '不能把角色改到这个群友名下：车次 % 里已经有他的另一个角色了', v_run
      USING ERRCODE = 'check_violation',
            HINT = '先从该车次移除其中一个角色，再改属主';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_character_owner_change
    BEFORE UPDATE OF player_id ON "character"
    FOR EACH ROW EXECUTE FUNCTION check_character_owner_change();
