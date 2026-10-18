/**
 * 融合车（合并车次）的共享逻辑。
 *
 * 为什么要有这个文件：融合车是「要打全打」的一个整体，但底层数据是
 * **每个成员 BOSS 各一条车次记录**。合并展示 / 合并状态这些规则如果每个页面各写一份，
 * 必然出现口径漂移（曾经就出现过：boss排班 合并了、「我的车次」没有，导致一趟车显示成 7 张卡）。
 * 所以这里集中放，所有页面共用。
 *
 * 主 BOSS 约定：融合车成员的**第一个**（后端按 group.sortOrder, boss.sortOrder 排序）。
 * 融合车的「第 N 趟」「几趟车」一律以主 BOSS 为准，避免各 BOSS 历史趟数不一致时冒出幻影车次。
 */

import type { BossView, PlayerRunView, RunView } from '../api/types';

/** 一个融合车条目（用于 BOSS 列表 / 下拉框）。 */
export interface ComboEntry {
  /** 合成的虚拟 BOSS（id 为负数，避免与真实 BOSS 冲突） */
  boss: BossView;
  /** 该融合车的全部成员 BOSS */
  members: BossView[];
  /** 主 BOSS id（任何要写后端的 bossId 都必须用它，不能用虚拟的负数 id） */
  primaryBossId: number;
  /** 融合车标记 */
  comboKey: string;
}

/**
 * 把 BOSS 列表转换成「列表页要展示的条目」：融合车合并成一个虚拟条目，其余原样。
 * 保持原有的分组与顺序（融合车出现在它第一个成员的位置）。
 */
export function groupBossesForDisplay(bosses: BossView[]): ComboEntry[] {
  const seen = new Set<string>();
  const out: ComboEntry[] = [];
  for (const boss of bosses) {
    if (boss.comboKey) {
      if (seen.has(boss.comboKey)) {
        continue;
      }
      seen.add(boss.comboKey);
      const members = bosses.filter((b) => b.comboKey === boss.comboKey);
      out.push({
        boss: {
          ...boss,
          id: -Math.abs(members[0].id),
          name: boss.comboName ?? '融合车',
          iconKey: members.map((m) => m.iconKey).join(','),
          tiers: boss.comboTier ? [boss.comboTier] : boss.tiers,
          tier: boss.comboTier ?? boss.tier,
          capacity: members[0].capacity,
        },
        members,
        primaryBossId: members[0].id,
        comboKey: boss.comboKey,
      });
      continue;
    }
    out.push({ boss, members: [], primaryBossId: boss.id, comboKey: '' });
  }
  return out;
}

/** 某辆融合车（按车次序号标识）在全部成员 BOSS 上的车次记录。 */
export interface ComboCar {
  runIndex: number;
  /** 主 BOSS 的那条记录 */
  primary: RunView;
  /** 同一趟在全部成员 BOSS 上的记录 id（融合车操作要同步这些） */
  runIds: number[];
}

/**
 * 把车次按「融合车的一趟」聚合。
 *
 * @param runs            当前周期已加载的全部车次
 * @param comboMembers    融合车的成员 BOSS（空数组表示不是融合车）
 * @param tier            可选：只聚合该难度的记录
 */
export function groupRunsIntoComboCars(
  runs: RunView[],
  comboMembers: BossView[],
  tier?: string,
): ComboCar[] {
  if (comboMembers.length === 0) {
    return [];
  }
  const memberIds = new Set(comboMembers.map((m) => m.id));
  const primaryBossId = comboMembers[0].id;
  const pool = runs.filter(
    (r) => memberIds.has(r.bossId) && (!tier || r.tier === tier),
  );
  const byIndex = new Map<number, RunView[]>();
  for (const run of pool) {
    const list = byIndex.get(run.runIndex) ?? [];
    list.push(run);
    byIndex.set(run.runIndex, list);
  }
  return [...byIndex.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([runIndex, list]) => ({
      runIndex,
      // 主 BOSS 的那条作为代表；万一缺失就退化为第一条
      primary: list.find((r) => r.bossId === primaryBossId) ?? list[0],
      runIds: list.map((r) => r.id),
    }));
}

/**
 * runId → 同一趟融合车的全部 runId。
 * 用于「标记完成」这类要同步到每个成员 BOSS 的操作。
 */
export function buildComboRunGroups(
  runs: RunView[],
  bosses: BossView[],
): Map<number, number[]> {
  const result = new Map<number, number[]>();
  const byCombo = new Map<string, BossView[]>();
  for (const boss of bosses) {
    if (boss.comboKey) {
      const list = byCombo.get(boss.comboKey) ?? [];
      list.push(boss);
      byCombo.set(boss.comboKey, list);
    }
  }
  for (const members of byCombo.values()) {
    for (const car of groupRunsIntoComboCars(runs, members)) {
      for (const runId of car.runIds) {
        result.set(runId, car.runIds);
      }
    }
  }
  return result;
}

/**
 * 「我的车次」的展示项。
 * 一趟融合车只出一条 —— 群友不需要为同一趟车点 7 次「已完成」。
 */
export interface MergedPlayerRun extends PlayerRunView {
  /** 这一趟涉及的全部 runId（融合车会是同趟的多个成员 BOSS） */
  groupRunIds: number[];
  /** 融合车的成员 BOSS 名（用于展示「融合车（N 连）」） */
  comboMemberNames?: string[];
}

/**
 * 把「我的车次」按融合车合并。
 *
 * 合并规则：同 `runIndex`、且属于同一辆融合车（通过 runId→group 映射判断）的记录合成一条。
 * 状态取主 BOSS 的（正常情况下融合车各条状态是一致的）。
 */
export function mergeMyRuns(
  mine: PlayerRunView[],
  runs: RunView[],
  bosses: BossView[],
): MergedPlayerRun[] {
  const groups = buildComboRunGroups(runs, bosses);
  const bossById = new Map(bosses.map((b) => [b.id, b]));
  const consumed = new Set<number>();
  const out: MergedPlayerRun[] = [];

  // 先按 runIndex + 融合车标记分组
  const byKey = new Map<string, PlayerRunView[]>();
  for (const item of mine) {
    const group = groups.get(item.runId);
    const comboKey = group ? bossById.get(item.bossId)?.comboKey ?? null : null;
    // 非融合车：自己一组（runId 唯一）
    const key = comboKey ? `${comboKey}|${item.runIndex}` : `single|${item.runId}`;
    const list = byKey.get(key) ?? [];
    list.push(item);
    byKey.set(key, list);
  }

  for (const list of byKey.values()) {
    // 有一条是融合车成员就按融合车处理
    const allRunIds = new Set<number>();
    for (const item of list) {
      (groups.get(item.runId) ?? [item.runId]).forEach((id) => allRunIds.add(id));
    }

    // 主 BOSS 的记录作为代表（用 runs 里的 bossId 判断）
    const comboKey = list[0].bossId ? bossById.get(list[0].bossId)?.comboKey ?? null : null;
    let primary = list[0];
    if (comboKey) {
      const members = bosses.filter((b) => b.comboKey === comboKey);
      const primaryBossId = members[0]?.id;
      primary = list.find((x) => x.bossId === primaryBossId) ?? list[0];
    }
    if (consumed.has(primary.runId)) {
      continue;
    }
    list.forEach((x) => consumed.add(x.runId));

    out.push({
      ...primary,
      groupRunIds: [...allRunIds],
      comboMemberNames: comboKey
        ? bosses.filter((b) => b.comboKey === comboKey).map((b) => b.name)
        : undefined,
    });
  }

  /**
   * 排序规则（「我的车次」页用）：
   *   1. **未打完的排前面**，已完成的沉到后面 —— 打开页面第一眼看到的就是还要打的
   *   2. 同一组内按 BOSS 名归类（同 BOSS 的趟次挨在一起）
   *   3. 最后按趟数升序
   *
   * 为什么把「已完成」放后面：这个页面的主要用途是「看我还剩哪些没打」，
   * 打完之后它就从关注列表里退场，不该继续占据靠前的位置。
   */
  // 排序交给 sortMyRuns（单一实现，有单元测试覆盖）
  return sortMyRuns(out);
}

/**
 * 「我的车次」的排序（纯函数，可单元测试）。
 *
 * 规则：
 *   1. 未打完的排前面，已完成的沉到后面 —— 打开页面第一眼看到还需打什么
 *   2. 同一组内按 BOSS 名归类（同 BOSS 的趟次挨在一起）
 *   3. 最后按趟数升序
 *
 * mergeMyRuns 内部调用它，抽出来是为了能单独测排序行为。
 */
export function sortMyRuns<T extends { status: string; bossName: string; runIndex: number }>(
  items: T[],
): T[] {
  return [...items].sort((a, b) => {
    const aDone = a.status === 'DONE' ? 1 : 0;
    const bDone = b.status === 'DONE' ? 1 : 0;
    if (aDone !== bDone) {
      return aDone - bDone; // 未完成(0) 在前，已完成(1) 在后
    }
    if (a.bossName !== b.bossName) {
      return a.bossName.localeCompare(b.bossName);
    }
    return a.runIndex - b.runIndex;
  });
}
