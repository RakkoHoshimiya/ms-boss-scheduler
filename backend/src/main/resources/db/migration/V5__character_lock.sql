-- ============================================================================
-- V5：补上「角色还没上车」时的锁缺口 —— 引入角色级锁，固定「角色 → BOSS」顺序
--
-- 背景（第六轮评审 P1）：
--   V4 的角色属主触发器只锁「该角色当前**已提交**的席位所在的 BOSS」。
--   如果角色还没有席位（或另一个事务刚给它加了席位但未提交），
--   这个查询找不到要锁的 BOSS，于是：
--
--     事务 A：把玩家甲的角色 C 加进一趟已经有玩家乙角色的车（未提交）
--     事务 B：把 C 的属主改成乙（B 查不到 A 的未提交席位 → 不锁 BOSS）
--     A 的延迟检查在提交时读 C 的属主（B 还没提交 → 读到旧属主甲）→ 通过
--     B 的检查在更新时读 C 的席位（A 还没提交 → 没有席位）→ 通过
--     两个都提交 → 车上出现乙的两个角色，两边都没拦住（典型 write skew）
--
-- 解决：
--   1. 新增**角色级**咨询锁 gmsr_lock_character(character_id)
--   2. 席位写入（INSERT/UPDATE）用一个**立即**触发器先锁角色、再锁 BOSS
--      （计数仍在延迟触发器里做，这里只负责加锁）
--   3. 角色改属主同样先锁角色、再锁 BOSS
--   两条路径的加锁顺序一致（角色 → BOSS），因此不会死锁，且互相串行化：
--   后到的一方一定能看到先到一方已提交的状态。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 1) 角色级锁（命名空间 2；BOSS 锁在命名空间 1）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION gmsr_lock_character(p_character_id bigint)
    RETURNS void
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF p_character_id IS NOT NULL THEN
    PERFORM pg_advisory_xact_lock(2, p_character_id::int);
  END IF;
END;
$$;

-- ---------------------------------------------------------------------------
-- 2) 席位写入时立即加锁（只加锁，不做校验）
--    必须放在 BEFORE INSERT OR UPDATE，这样锁在语句执行时就拿到，
--    而不是等提交时（延迟触发器）——那是 write skew 的根源。
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION lock_run_member_rows() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  -- 顺序固定：先角色，后 BOSS
  PERFORM gmsr_lock_character(NEW.character_id);

  PERFORM gmsr_lock_bosses(
      (SELECT boss_id FROM boss_run WHERE id = NEW.run_id),
      CASE WHEN TG_OP = 'UPDATE' THEN (SELECT boss_id FROM boss_run WHERE id = OLD.run_id) END);

  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_lock_run_member ON run_member;
CREATE TRIGGER trg_lock_run_member
    BEFORE INSERT OR UPDATE ON run_member
    FOR EACH ROW EXECUTE FUNCTION lock_run_member_rows();

-- ---------------------------------------------------------------------------
-- 3) 角色改属主：也先锁角色，再锁它所在所有车次的 BOSS
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_character_owner_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_boss_ids bigint[];
  v_run      bigint;
BEGIN
  IF NEW.player_id IS NOT DISTINCT FROM OLD.player_id THEN
    RETURN NEW;
  END IF;

  -- 先锁角色：与「给这个角色加席位」共用一把锁，谁先谁后都能看到对方的提交
  PERFORM gmsr_lock_character(NEW.id);

  SELECT array_agg(DISTINCT r.boss_id) INTO v_boss_ids
    FROM run_member m
    JOIN boss_run r ON r.id = m.run_id
   WHERE m.character_id = NEW.id;

  PERFORM gmsr_lock_bosses(VARIADIC COALESCE(v_boss_ids, ARRAY[]::bigint[]));

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

-- ---------------------------------------------------------------------------
-- 4) 角色删除/换人时不需要额外处理：席位有外键，角色删不掉
-- ---------------------------------------------------------------------------
