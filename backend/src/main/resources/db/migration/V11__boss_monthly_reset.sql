-- 按月刷新的 BOSS（如「老黑能量车」）不再跟随每周四的完成状态重置，
-- 改为每月 1 号 08:00 单独重置，因此需要给 boss 打一个标记。
--
-- V1__baseline.sql 的建表语句里本来就有 monthly 列，但老库可能是
-- 「baseline 到 V1、V1 未实际执行」建起来的，那里没有这一列；
-- 用 IF NOT EXISTS 保证两种情况都能跑通（Hibernate ddl-auto=validate 要求它存在）。
ALTER TABLE boss ADD COLUMN IF NOT EXISTS monthly boolean DEFAULT false NOT NULL;

COMMENT ON COLUMN boss.monthly IS 'true = 按月刷新（每月 1 号 08:00 重置），不跟随每周四重置';
