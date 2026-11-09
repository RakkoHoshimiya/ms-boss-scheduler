-- ============================================================================
-- V4：给「跨表不变量」一个统一的锁协议
--
-- 背景（第五轮评审 P1）：
--   V3 的三个触发器各自做 count 检查，但它们看不到对方**未提交**的修改。
--   典型交错：
--     事务 A：给车次加人（尚未提交）
--     事务 B：把这个 BOSS 的容量改小
--   B 数不到 A 的新席位，A 也读不到 B 的新容量 —— 两边都通过，提交后超员。
--   角色改属主与加人并发也有同类问题。
--
-- 解决：所有会改变这些不变量的写入，先抢**同一把 BOSS 级咨询锁**
--   pg_advisory_xact_lock(命名空间, boss_id)
-- 锁随事务结束自动释放。抢到锁之后再 count，读到的就是对方已提交的状态；
-- 抢不到就排队，从而把「同一 BOSS 下的容量/席位/属主变更」串行化。
--
-- 锁顺序：涉及多个 BOSS 时一律按 boss_id 升序获取，避免死锁。
-- ============================================================================

-- 统一的加锁函数：按 id 升序依次加锁（单次调用通常只有一个 id）
CREATE OR REPLACE FUNCTION gmsr_lock_bosses(VARIADIC p_boss_ids bigint[])
    RETURNS void
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_id bigint;
BEGIN
  FOR v_id IN
      SELECT DISTINCT b FROM unnest(p_boss_ids) AS b
       WHERE b IS NOT NULL
       ORDER BY b
  LOOP
    PERFORM pg_advisory_xact_lock(20261031, v_id::int);
  END LOOP;
END;
$$;

-- ---------------------------------------------------------------------------
-- 1) 席位不变量：先锁 BOSS 再计数
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_run_member_invariant() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_boss_id    bigint;
  v_capacity   integer;
  v_members    integer;
  v_player_id  bigint;
  v_dup        integer;
BEGIN
  SELECT b.id, b.capacity INTO v_boss_id, v_capacity
    FROM boss_run r
    JOIN boss b ON b.id = r.boss_id
   WHERE r.id = NEW.run_id;
  IF v_capacity IS NULL THEN
    RETURN NULL;  -- 车次不存在：交给外键去报错
  END IF;

  -- 关键：先拿锁，再计数。否则读不到并发事务刚改的容量/席位
  PERFORM gmsr_lock_bosses(v_boss_id);

  -- 重新读一次容量（拿到锁之后的值才是最新的）
  SELECT capacity INTO v_capacity FROM boss WHERE id = v_boss_id;

  SELECT count(*) INTO v_members
    FROM run_member
   WHERE run_id = NEW.run_id
     AND id <> NEW.id;
  IF v_members >= v_capacity THEN
    RAISE EXCEPTION '这趟车已满 % 人，不能再加席位', v_capacity
      USING ERRCODE = 'check_violation',
            HINT = '请先在页面上移除成员，或调大该 BOSS 的容量';
  END IF;

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

  RETURN NULL;
END;
$$;

-- ---------------------------------------------------------------------------
-- 2) BOSS 容量变更：同一把锁，改完立刻校验
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_boss_capacity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_over integer;
  v_run  bigint;
BEGIN
  PERFORM gmsr_lock_bosses(NEW.id);

  SELECT r.id, count(m.id) INTO v_run, v_over
    FROM boss_run r
    JOIN run_member m ON m.run_id = r.id
   WHERE r.boss_id = NEW.id
   GROUP BY r.id
  HAVING count(m.id) > NEW.capacity
   LIMIT 1;

  IF v_over IS NOT NULL THEN
    RAISE EXCEPTION '容量不能改成 %：车次 % 已经有 % 个席位', NEW.capacity, v_run, v_over
      USING ERRCODE = 'check_violation',
            HINT = '先移除超出的席位，再调小容量';
  END IF;

  RETURN NEW;
END;
$$;

-- ---------------------------------------------------------------------------
-- 3) 车次改绑 BOSS：锁新 BOSS 后再校验
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

  -- 新旧 BOSS 都锁（按 id 升序，函数内部已排序）
  PERFORM gmsr_lock_bosses(OLD.boss_id, NEW.boss_id);

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

-- ---------------------------------------------------------------------------
-- 4) 角色改属主：锁住该角色所在所有车次的 BOSS 再校验
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
