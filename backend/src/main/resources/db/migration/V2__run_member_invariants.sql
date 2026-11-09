-- ============================================================================
-- V2：把两条排班不变量下沉到数据库层
--
--   1. 一趟车的席位数不能超过该 BOSS 的容量（boss.capacity）
--   2. 同一趟车里，同一个群友只能有一个角色
--
-- 为什么还要数据库约束：
--   应用层已经有「车次行锁 + 检查」，但那只保护走过 RunService 的写入。
--   一旦有人直连数据库、写脚本批量导入、或者将来新增了别的写入路径，
--   不变量就没人看着了。数据库约束是最后一道防线。
--
-- 注意：触发器里的 count 检查**不是并发安全的**（两个并发事务可能都数到 N-1）。
--   并发正确性靠应用层对车次行加的悲观锁；这里只是兜底，
--   防止绕过应用的写入破坏数据。
--
-- 上线前已体检生产数据：无超员、无同人双角色，因此可以直接加约束。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 0) 先清理座位号重复，再加唯一约束
--    历史数据里有 6 处重复（旧代码用「人数+1」分配座位号，删过人以后会撞车）。
--    这里按 (slot_no, id) 顺序就地重排成 1..n。
--    这一步必须在创建触发器之前做完，否则重排会逐行触发校验。
-- ---------------------------------------------------------------------------
WITH ranked AS (
  SELECT id,
         row_number() OVER (PARTITION BY run_id ORDER BY slot_no, id) AS new_slot
    FROM run_member
)
UPDATE run_member m
   SET slot_no = ranked.new_slot
  FROM ranked
 WHERE m.id = ranked.id
   AND m.slot_no <> ranked.new_slot;

CREATE UNIQUE INDEX uq_run_slot ON run_member (run_id, slot_no);

-- ---------------------------------------------------------------------------
-- 1) 席位不变量：容量 + 同一群友单角色
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
    RETURN NEW;  -- 车次不存在：交给外键去报错
  END IF;

  -- 1) 容量：排除自己（UPDATE 场景）
  SELECT count(*) INTO v_members
    FROM run_member
   WHERE run_id = NEW.run_id
     AND (TG_OP = 'INSERT' OR id <> NEW.id);
  IF v_members >= v_capacity THEN
    RAISE EXCEPTION '这趟车已满 % 人，不能再加席位', v_capacity
      USING ERRCODE = 'check_violation',
            HINT = '请先在页面上移除成员，或调大该 BOSS 的容量';
  END IF;

  -- 2) 同一群友单角色
  SELECT c.player_id INTO v_player_id
    FROM "character" c WHERE c.id = NEW.character_id;
  IF v_player_id IS NOT NULL THEN
    SELECT count(*) INTO v_dup
      FROM run_member m
      JOIN "character" c ON c.id = m.character_id
     WHERE m.run_id = NEW.run_id
       AND (TG_OP = 'INSERT' OR m.id <> NEW.id)
       AND c.player_id = v_player_id;
    IF v_dup > 0 THEN
      RAISE EXCEPTION '同一趟车里一个群友只能上一个角色'
        USING ERRCODE = 'check_violation',
              HINT = '该群友已经有别的角色在这趟车上';
    END IF;
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_run_member_invariant
    BEFORE INSERT OR UPDATE ON run_member
    FOR EACH ROW EXECUTE FUNCTION check_run_member_invariant();

-- ---------------------------------------------------------------------------
-- 2) BOSS 容量不能被改到比现有车次的席位数还小
--    （否则会出现「已经超员但没人发现」的状态）
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION check_boss_capacity() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
DECLARE
  v_over integer;
  v_run  bigint;
BEGIN
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

CREATE TRIGGER trg_boss_capacity
    BEFORE UPDATE OF capacity ON boss
    FOR EACH ROW EXECUTE FUNCTION check_boss_capacity();
