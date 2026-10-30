import { useEffect, useMemo, useRef, useState, useCallback } from 'react';
import { api } from '../api/client';
import { useDevice } from '../components/DeviceContext';
import { groupBossesForDisplay } from '../features/combo';
import type { BossView, GroupView, PlayerView, RunView } from '../api/types';
import { useToast } from '../components/Toast';
import Modal from '../components/Modal';
import BossIcon from '../components/BossIcon';
import RunFormModal from '../components/RunFormModal';
import {
  memberStatusLabel,
  memberTooltip,
  sortTiers,
  statusLabel,
  tierClass,
  tierText,
  TIER_OPTIONS,
  TIER_ORDER,
} from '../components/ui';

/** 正在拖动的内容 */
type DragPayload =
  | { kind: 'member'; memberId: number; runId: number; label: string }
  | { kind: 'character'; characterId: number; label: string }
  /**
   * 融合车的一组车次：同一个车次序号在 7 个成员 BOSS 上各有一条记录，
   * 改阵容时这 7 条必须同步，才能保证「要打全打」。
   */
  | { kind: 'combo-member'; memberId: number; runIds: number[]; label: string };

/**
 * boss排班：管理员专属。
 * 点开某个 BOSS 查看它的所有车次，阵容支持拖拽调车（从 A 车拖到 B 车），
 * 也支持新增 / 复制 / 删除车次。
 */
export default function BossSchedulePage() {
  const { notify } = useToast();
  /** 触屏设备（手机/平板）不支持 HTML5 拖拽，要改用「点选 → 点目标」 */
  const { touch } = useDevice();

  const [bosses, setBosses] = useState<BossView[]>([]);
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [players, setPlayers] = useState<PlayerView[]>([]);
  const [selectedTier, setSelectedTier] = useState<string>('');
  /** 角色池按群友（显示名）筛选，空 = 全部 */
  const [poolPlayer, setPoolPlayer] = useState('');
  const [currentBoss, setCurrentBoss] = useState<BossView | null>(null);
  /** 当前选中的是融合车时，这里放它的全部成员 BOSS（否则为空） */
  const [currentCombo, setCurrentCombo] = useState<BossView[]>([]);
  const [runs, setRuns] = useState<RunView[]>([]);
  /** 当前 runs 是按哪一组 BOSS 加载的（切换 BOSS 时用它判断数据是否已经换过来） */
  const [runsLoadedFor, setRunsLoadedFor] = useState('');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  /**
   * 触屏设备上的「已选中」内容。
   * 手机浏览器不支持 HTML5 拖拽，所以排班改用：点一下选中 → 再点目标执行。
   */
  const [touchSelected, setTouchSelected] = useState<
    | {
        kind: 'member' | 'combo-member' | 'character';
        memberId?: number;
        runId?: number;
        runIds?: number[];
        characterId: number;
        playerId: number;
        label: string;
      }
    | null
  >(null);
  const [drag, setDrag] = useState<DragPayload | null>(null);
  /** 拖拽内容的「真身」：拖放期间 state 可能被 dragleave 清掉，用 ref 保证 drop 一定能读到 */
  const dragRef = useRef<DragPayload | null>(null);
  const setDragPayload = (payload: DragPayload | null) => {
    dragRef.current = payload;
    setDrag(payload);
  };
  const [dragOverRun, setDragOverRun] = useState<number | null>(null);
  /** 当前拖到哪个成员席位上（用于换位高亮） */
  const [dragOverMemberId, setDragOverMemberId] = useState<number | null>(null);
  /** 正在拖拽的角色所属玩家（用于判断换位落点是否会造成「同一人两角色同车」） */
  const [dragPlayerId, setDragPlayerId] = useState<number | null>(null);

  const [bossForm, setBossForm] = useState<BossView | 'new' | null>(null);
  const [runForm, setRunForm] = useState<{ bossId: number; bossName: string; tier?: string; run?: RunView } | null>(
    null
  );
  const [newGroupName, setNewGroupName] = useState('');

  const loadBase = () => {
    setLoading(true);
    Promise.all([api.bosses(), api.groups(), api.players()])
      .then(([bossList, groupList, playerList]) => {
        setBosses(bossList);
        setGroups(groupList);
        setPlayers(playerList);
      })
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(loadBase, []);

  /** 换 BOSS 时重置角色池筛选 */
  useEffect(() => {
    setPoolPlayer('');
  }, [currentBoss?.id]);

  /**
   * 加载车次。
   *
   * 现在没有「周」的概念了，一次取回全部车次，再按当前 BOSS 过滤 ——
   * 数据量很小（几十到几百条），比原来按周分别请求更简单也更快。
   */
  const loadRuns = (bossIds: number[]) => {
    if (bossIds.length === 0) {
      return;
    }
    api
      .runs()
      .then((all) => {
        // 融合车：把全部成员 BOSS 的车次都取回来，前端再按车次序号聚合成一张卡
        setRuns(all.filter((run) => bossIds.includes(run.bossId)));
        setRunsLoadedFor(bossIds.join(','));
      })
      .catch((error) => notify(error instanceof Error ? error.message : '加载车次失败', 'error'));
  };

  /** 当前视图涉及的 BOSS id：融合车是全部成员。 */
  const viewBossIds = useMemo(
    () => (currentCombo.length > 0 ? currentCombo.map((b) => b.id) : currentBoss ? [currentBoss.id] : []),
    [currentCombo, currentBoss],
  );

  useEffect(() => {
    if (viewBossIds.length > 0) {
      loadRuns(viewBossIds);
    }
  }, [viewBossIds.join(',')]);

  /** 这个 BOSS 实际存在的难度（来自 BOSS 配置，而不是「本周排过什么」） */
  const availableTiers = useMemo(() => {
    const configured = currentBoss?.tiers ?? [];
    if (configured.length > 0) {
      return sortTiers(configured);
    }
    const used = runs.map((run) => run.tier);
    return sortTiers(used.length > 0 ? used : [currentBoss?.tier ?? 'N']);
  }, [currentBoss?.tiers, currentBoss?.tier, runs]);

  /** 记录「已经为哪个 BOSS 自动选过难度」，避免用户手选后被改回去 */
  const tierPickedFor = useRef<number | null>(null);

  /**
   * 打开某个 BOSS 时自动选一个难度。
   *
   * 规则：优先选**真的排过车**的难度。
   * 以前固定取「难度清单里的第一个」，于是「监视者卡尔洛斯」清单是 E/N/C/EX、
   * 车却都排在 Normal，一进来就显示 Easy 和 0 趟，看着像坏了。
   */
  useEffect(() => {
    if (!currentBoss || availableTiers.length === 0) {
      return;
    }
    if (tierPickedFor.current === currentBoss.id) {
      return; // 这个 BOSS 已经选过，尊重用户后续的手动切换
    }
    if (runsLoadedFor !== viewBossIds.join(',')) {
      return; // 车次还是上一个 BOSS 的，等它换过来再判断
    }
    const withRuns = availableTiers.find((tier) => runs.some((run) => run.tier === tier));
    setSelectedTier(withRuns ?? availableTiers[0]);
    tierPickedFor.current = currentBoss.id;
  }, [currentBoss?.id, availableTiers.join(','), runs, runsLoadedFor]);

  /**
   * 融合车的「主 BOSS」：趟数以它为准。
   *
   * 为什么需要主 BOSS：这 7 个 BOSS 在变成融合车之前是各自独立排班的，
   * 各自的 runIndex 互不相干（比如绿水灵王有第 1/2/3 趟，其他 BOSS 只有第 1 趟）。
   * 如果按 runIndex 取并集，就会冒出「第 8 趟 / 第 9 趟」这种只有个别 BOSS 有记录的空车。
   * 所以融合车的趟数一律以主 BOSS 为准，其余成员 BOSS 同步跟进。
   */
  const primaryComboBossId = currentCombo.length > 0 ? currentCombo[0].id : null;

  /** 当前难度下的车次 */
  const visibleRuns = useMemo(
    () => (selectedTier ? runs.filter((run) => run.tier === selectedTier) : runs),
    [runs, selectedTier]
  );

  /**
   * 展示用的「卡片」：按车次序号聚合。
   * - 普通 BOSS：一个序号就是一条车次；
   * - 融合车：同一个序号在 7 个成员 BOSS 上各有一条记录，聚合成一张卡片，
   *   卡片上显示全部 BOSS 图标与名称，阵容取并集（正常情况下它们是一致的）。
   */
  interface RunCard {
    key: string;
    runIndex: number;
    primary: RunView;
    runIds: number[];
    isCombo: boolean;
    members: RunView['members'];
  }

  const runCards = useMemo<RunCard[]>(() => {
    if (currentCombo.length === 0) {
      return visibleRuns
        .slice()
        .sort((a, b) => a.runIndex - b.runIndex)
        .map((run) => ({
          /**
           * key 必须用**唯一 id**，不能用趟次序号。
           * 趟次是「每个难度各自编号」的，所以同一个 BOSS 可能同时有
           * Normal 第2趟 和 Chaos 第2趟 —— 用 runIndex 当 key 会撞车，
           * React 会复用同一个 DOM 节点，把其中一趟的内容渲染两次。
           */
          key: 'run-' + run.id,
          runIndex: run.runIndex,
          primary: run,
          runIds: [run.id],
          isCombo: false,
          members: run.members,
        }));
    }
    // 融合车：只按主 BOSS 的车次列表出卡片
    const primaryRuns = visibleRuns
      .filter((run) => run.bossId === primaryComboBossId)
      .sort((a, b) => a.runIndex - b.runIndex);
    return primaryRuns.map((run) => {
      // 同一趟在其它成员 BOSS 上的记录（正常情况下都有，缺了也能显示）
      // 只合并「同难度 + 同融合车成员」的记录，
      // 否则另一个难度的同序号车次会被并进这张卡，做删除/移除时误伤
      const siblings = runs.filter(
        (r) => r.runIndex === run.runIndex && r.tier === run.tier && viewBossIds.includes(r.bossId),
      );
      return {
        key: 'combo-' + run.id,
        runIndex: run.runIndex,
        primary: run,
        runIds: siblings.length > 0 ? siblings.map((r) => r.id) : [run.id],
        isCombo: true,
        members: run.members,
      };
    });
  }, [visibleRuns, runs, currentCombo.length, primaryComboBossId]);

  /** 融合车页面上「数据不一致」的历史遗留：非主 BOSS 上对不上主车次的记录。 */
  const strayRuns = useMemo(() => {
    if (currentCombo.length === 0) {
      return [] as RunView[];
    }
    const primaryIndexes = new Set(
      runs.filter((r) => r.bossId === primaryComboBossId).map((r) => r.runIndex),
    );
    return runs.filter((r) => r.bossId !== primaryComboBossId && !primaryIndexes.has(r.runIndex));
  }, [runs, currentCombo.length, primaryComboBossId]);

  /** 重新加载当前视图的车次（融合车会一次拿回全部成员 BOSS）。 */
  const reloadRuns = async () => {
    if (viewBossIds.length === 0) {
      return;
    }
    const all = await api.runs();
    setRuns(all.filter((run) => viewBossIds.includes(run.bossId)));
  };

  /** 车次上已有的角色 id，用于从待选池里排除 */
  const usedCharacterIds = useMemo(() => {
    const set = new Set<number>();
    runs.forEach((run) => run.members.forEach((m) => set.add(m.characterId)));
    return set;
  }, [runs]);

  const poolCharacters = useMemo(
    () =>
      players
        .flatMap((player) =>
          player.characters
            .filter((c) => !usedCharacterIds.has(c.id))
            .map((c) => ({ ...c, playerNickname: player.nickname })),
        )
        /**
         * 战力从高到低。
         *
         * 拖车上人时通常先看高战力角色，所以默认按战力降序。
         * 战力相同再按「群友昵称 → 职业」排，保证顺序稳定 ——
         * 否则同战力角色的先后会随接口返回顺序变化，池子看起来会「跳」。
         */
        .sort(
          (a, b) =>
            b.power - a.power ||
            a.playerNickname.localeCompare(b.playerNickname, 'zh-Hans-CN') ||
            a.job.localeCompare(b.job, 'zh-Hans-CN'),
        ),
    [players, usedCharacterIds],
  );

  /** 待选池里出现过的群友，用于下拉筛选 */
  const poolPlayers = useMemo(() => {
    const names = new Set(poolCharacters.map((c) => c.playerNickname));
    return Array.from(names).sort((a, b) => a.localeCompare(b, 'zh-Hans-CN'));
  }, [poolCharacters]);

  /** 按群友筛选后的待选角色 */
  const visiblePoolCharacters = useMemo(
    () => (poolPlayer ? poolCharacters.filter((c) => c.playerNickname === poolPlayer) : poolCharacters),
    [poolCharacters, poolPlayer],
  );

  /** 同属一个融合车的全部 BOSS（按配置顺序）。 */
  const comboBossesOf = useCallback(
    (comboKey: string | null | undefined) =>
      comboKey ? bosses.filter((b) => b.comboKey === comboKey) : [],
    [bosses],
  );

  /**
   * BOSS 列表页要展示的条目：融合车合并成一个「虚拟 BOSS」（id 取负数，避免和真实 BOSS 冲突），
   * 其余 BOSS 原样展示。
   */
  /**
   * BOSS 列表要展示的条目：融合车合并成一个长条大框。
   * 合并规则来自共享模块 features/combo.ts，与「车次一览表」「我的车次」保持同一口径。
   */
  const groupedDisplay = useMemo(() => {
    const map = new Map<string, BossView[]>();
    for (const entry of groupBossesForDisplay(bosses)) {
      const list = map.get(entry.boss.groupName) ?? [];
      list.push(entry.boss);
      map.set(entry.boss.groupName, list);
    }
    return Array.from(map.entries());
  }, [bosses]);

  /** 打开一个 BOSS：融合车条目要把成员 BOSS 一起记下来。 */
  const openBoss = (entry: BossView) => {
    if (entry.id < 0 || entry.comboKey) {
      const members = comboBossesOf(entry.comboKey);
      setCurrentCombo(members);
      setCurrentBoss(entry);
      setSelectedTier(entry.comboTier ?? 'H');
      return;
    }
    setCurrentCombo([]);
    setSelectedTier('');
    setCurrentBoss(entry);
  };

  /**
   * 融合车操作的核心：**按角色 id 在每条车次上分别定位席位**。
   *
   * 为什么必须这样：融合车在数据层是「每个成员 BOSS 各一条 boss_run」，
   * 同一个角色在这 7 条车次上各有**自己独立的 run_member 行**（memberId 完全不同，
   * 例如群友G/剑豪在 7 条上是 319/323/327/331/335/339/343）。
   * 后端 RunController 又是按 memberId 定位席位的 —— 所以绝不能拿同一个 memberId
   * 去循环操作多个 runId（第 2 次起必然失败）。
   */
  const memberIdOfCharacter = (runId: number, characterId: number): number | null => {
    const run = runs.find((r) => r.id === runId);
    const member = run?.members.find((m) => m.characterId === characterId);
    return member?.id ?? null;
  };

  /**
   * 合并车的「主 BOSS id」。
   * 融合车条目用的是前端合成的负数 id（只用于列表区分），发给后端会 404，
   * 所以任何要写后端的 bossId 都必须用真实的主 BOSS id。
   */
  const primaryBossId = currentCombo.length > 0 ? currentCombo[0].id : currentBoss?.id ?? 0;

  const grouped = useMemo(() => {
    const map = new Map<string, BossView[]>();
    bosses.forEach((boss) => {
      const list = map.get(boss.groupName) ?? [];
      list.push(boss);
      map.set(boss.groupName, list);
    });
    return Array.from(map.entries());
  }, [bosses]);

  /** 拖拽落点：把席位或待选角色放到目标车次 */
  const handleDrop = async (event: React.DragEvent, targetRun: RunView, targetRunIds?: number[]) => {
    // 融合车：这一张卡对应 7 条车次记录，改动要同步到全部
    const targetIds = targetRunIds && targetRunIds.length > 0 ? targetRunIds : [targetRun.id];
    const isComboTarget = targetIds.length > 1;
    event.preventDefault();
    event.stopPropagation();
    setDragOverRun(null);
    const payload = dragRef.current;
    if (!payload || busy) {
      setDragPayload(null);
      return;
    }
    const drag = payload;
    if (drag.kind === 'member' || drag.kind === 'combo-member') {
      const dragRunIds = drag.kind === 'member' ? [drag.runId] : drag.runIds;
      // 同一张卡片内拖动无意义
      if (dragRunIds.length > 0 && dragRunIds[0] === targetRun.id) {
        setDragPayload(null);
        return;
      }
      // 目标车次已满时：直接换位，而不是报「已满」。
      // 场景：同一 BOSS 下我 A 车上了阴阳师、B 车上了剑豪，两车都满了；
      // 拖到 B 车想表达的是「这两个角色对调」，硬塞进去必然触发容量限制。
      if (targetRun.memberCount >= targetRun.capacity && targetRun.members.length > 0) {
        // 挑一个「换过去不会造成同一人两角色同车」的对手：
        // 被拖角色的玩家在目标车上没有别的角色时，才能与之换位。
        const sourceRun = runs.find((r) => r.id === dragRunIds[0]);
        const dragged = sourceRun?.members.find((m) => m.id === drag.memberId);
        const swapTarget =
          dragged != null
            ? targetRun.members.find((m) => m.playerId !== dragged.playerId)
            : targetRun.members[0];
        if (!swapTarget) {
          notify(
            '「' +
              (dragged?.playerNickname ?? drag.label) +
              '」在目标车上已经有角色了，不能再换位',
            'error',
          );
          setDragPayload(null);
          return;
        }
        setBusy(true);
        try {
          if (isComboTarget) {
            // 融合车：按 characterId 在每条车次上各自定位席位，逐对换位
            const characterId = dragged?.characterId;
            if (characterId == null) {
              throw new Error('找不到被拖拽的角色');
            }
            for (const sourceId of dragRunIds) {
              // 目标卡片里与 sourceId 同一成员 BOSS 的那条车次
              const sourceRun = runs.find((r) => r.id === sourceId);
              const targetSibling = runs.find(
                (r) => r.bossId === sourceRun?.bossId && targetIds.includes(r.id),
              );
              if (!targetSibling) {
                continue;
              }
              const sourceMemberId = memberIdOfCharacter(sourceId, characterId);
              const targetMemberId = memberIdOfCharacter(
                targetSibling.id,
                swapTarget.characterId,
              );
              if (sourceMemberId == null || targetMemberId == null) {
                continue;
              }
              await api.swapMembers(sourceId, sourceMemberId, targetMemberId);
            }
            await reloadRuns();
          } else {
            const swapped = await api.swapMembers(dragRunIds[0], drag.memberId, swapTarget.id);
            setRuns((prev) =>
              prev.map((run) =>
                run.id === swapped.source.id
                  ? swapped.source
                  : run.id === swapped.target.id
                    ? swapped.target
                    : run,
              ),
            );
          }
          notify(
            `车已满，已把「${drag.label}」与「${swapTarget.playerNickname} ${swapTarget.job}」换位`,
          );
        } catch (error) {
          notify(error instanceof Error ? error.message : '换位失败', 'error');
          // 多步操作可能只成功了一部分，失败也要让界面回到服务端真值
          if (isComboTarget) {
            await reloadRuns().catch(() => undefined);
          }
        } finally {
          setBusy(false);
          setDragPayload(null);
        }
        return;
      }
      setBusy(true);
      try {
        if (isComboTarget) {
          // 融合车：同一角色在 7 条车次上各有自己的席位，按 characterId 逐条定位再挪
          // （sourceRun/dragged 只在上面的「车已满」分支里定义过，这里要重新取）
          const draggedHere = runs
            .find((r) => r.id === dragRunIds[0])
            ?.members.find((m) => m.id === drag.memberId);
          const characterId = draggedHere?.characterId;
          if (characterId == null) {
            throw new Error('找不到被拖拽的角色');
          }
          for (const sourceId of dragRunIds) {
            const sourceRun = runs.find((r) => r.id === sourceId);
            const targetSibling = runs.find(
              (r) => r.bossId === sourceRun?.bossId && targetIds.includes(r.id),
            );
            const sourceMemberId = memberIdOfCharacter(sourceId, characterId);
            if (targetSibling && sourceMemberId != null) {
              await api.moveMember(sourceId, sourceMemberId, targetSibling.id);
            }
          }
          await reloadRuns();
        } else {
          const result = await api.moveMember(dragRunIds[0], drag.memberId, targetRun.id);
          setRuns((prev) =>
            prev.map((run) =>
              run.id === result.source.id ? result.source : run.id === result.target.id ? result.target : run,
            ),
          );
        }
        notify(`已把「${drag.label}」调到第 ${targetRun.runIndex} 趟车`);
      } catch (error) {
        notify(error instanceof Error ? error.message : '调车失败', 'error');
      } finally {
        setBusy(false);
      }
    } else {
      setBusy(true);
      try {
        if (isComboTarget) {
          // 融合车：人加进任意一条记录都要同步到全部，保证「要打全打」
          for (const runId of targetIds) {
            await api.addMember(runId, { characterId: drag.characterId });
          }
          await reloadRuns();
        } else {
          const updated = await api.addMember(targetIds[0], { characterId: drag.characterId });
          setRuns((prev) => prev.map((run) => (run.id === updated.id ? updated : run)));
        }
        notify(`已把「${drag.label}」排入第 ${targetRun.runIndex} 趟车`);
      } catch (error) {
        notify(error instanceof Error ? error.message : '上车失败', 'error');
      } finally {
        setBusy(false);
      }
    }
    setDragPayload(null);
  };

  /**
   * 触屏设备上「点选 → 点目标」的执行逻辑，语义与拖拽完全一致：
   * - 选中角色池里的角色 + 点某趟车   → 上车
   * - 选中某个席位   + 点另一趟车     → 调车
   * - 选中某个席位   + 点另一个席位   → 换位
   * 手机上没有拖拽，这是唯一能用的交互方式。
   */
  const applyTouchTarget = async (
    targetRun: RunView | null,
    targetRunIds: number[],
    targetMemberId: number,
    targetCharacterId: number,
    targetLabel: string,
  ) => {
    const selected = touchSelected;
    if (!selected || busy) {
      setTouchSelected(null);
      return;
    }
    setTouchSelected(null);

    // 情况一：选中的是「角色池里的角色」→ 上车（或与目标席位互换）
    if (selected.kind === 'character') {
      if (!targetRun) {
        return;
      }
      setBusy(true);
      try {
        const ids = targetRunIds.length > 0 ? targetRunIds : [targetRun.id];
        // 目标席位有人 → 先把那个人放回待选池，再上车（等价于互换）
        if (targetMemberId > 0) {
          for (const runId of ids) {
            const mid = memberIdOfCharacter(runId, targetCharacterId);
            if (mid != null) {
              await api.removeMember(runId, mid);
            }
          }
        }
        for (const runId of ids) {
          await api.addMember(runId, { characterId: selected.characterId });
        }
        await reloadRuns();
        notify(`已把「${selected.label}」排入第 ${targetRun.runIndex} 趟车`);
      } catch (error) {
        notify(error instanceof Error ? error.message : '上车失败', 'error');
        await reloadRuns().catch(() => undefined);
      } finally {
        setBusy(false);
      }
      return;
    }

    // 情况二：选中的是某个席位
    const sourceRunIds = selected.kind === 'combo-member' ? selected.runIds ?? [] : [selected.runId ?? 0];
    if (sourceRunIds.length === 0 || sourceRunIds[0] === 0) {
      return;
    }
    // 点到自己所在的那趟车 → 取消
    if (targetRun && sourceRunIds.includes(targetRun.id) && targetMemberId === 0) {
      return;
    }
    setBusy(true);
    try {
      if (targetRun && targetMemberId > 0) {
        // 点到另一个席位 → 换位（按 characterId 在每条车次上分别定位）
        for (const sourceId of sourceRunIds) {
          const sourceSibling = runs.find((r) => r.id === sourceId);
          const counterpart = runs.find(
            (r) => r.bossId === sourceSibling?.bossId && r.runIndex === targetRun.runIndex,
          );
          if (!counterpart) {
            continue;
          }
          const a = memberIdOfCharacter(sourceId, selected.characterId);
          const b = memberIdOfCharacter(counterpart.id, targetCharacterId);
          if (a != null && b != null) {
            await api.swapMembers(sourceId, a, b);
          }
        }
        notify(`已把「${selected.label}」与「${targetLabel}」换位`);
      } else if (targetRun) {
        // 点到另一趟车 → 调车
        for (const sourceId of sourceRunIds) {
          const sourceSibling = runs.find((r) => r.id === sourceId);
          const targetSibling = runs.find(
            (r) => r.bossId === sourceSibling?.bossId && targetRunIds.includes(r.id),
          );
          const mid = memberIdOfCharacter(sourceId, selected.characterId);
          if (targetSibling && mid != null) {
            await api.moveMember(sourceId, mid, targetSibling.id);
          }
        }
        notify(`已把「${selected.label}」调到第 ${targetRun.runIndex} 趟车`);
      }
      await reloadRuns();
    } catch (error) {
      notify(error instanceof Error ? error.message : '操作失败', 'error');
      await reloadRuns().catch(() => undefined);
    } finally {
      setBusy(false);
    }
  };

  /**
   * 拖到「某个已有成员」身上 → 换位。
   * 场景：同一 BOSS 下，A 车有我的阴阳师、B 车有我的剑豪；这两个角色不可能同时打同一个 BOSS，
   * 所以拖到对方身上时的正确语义是「两人对调」，而不是「塞进 B 车」（B 车往往已满）。
   */
  const handleSwap = async (
    event: React.DragEvent,
    targetRun: RunView,
    targetMemberId: number,
    targetLabel: string,
  ) => {
    event.preventDefault();
    // 必须阻止冒泡，否则会继续触发车次的 onDrop（那会走「塞进车里」的逻辑）
    event.stopPropagation();
    setDragOverRun(null);
    setDragOverMemberId(null);
    /**
     * 注意顺序：**必须先读 dragRef.current，再清空**。
     *
     * 之前这里写反了（先 setDragPayload(null) 再读），导致读到的永远是 null，
     * 于是每次拖到别人身上都直接 return —— 表现就是「拖过去没有任何变动」。
     * 这是拖拽换位完全失效的原因。
     */
    const payload = dragRef.current;
    if (!payload || (payload.kind !== 'member' && payload.kind !== 'combo-member') || busy) {
      setDragPayload(null);
      return;
    }
    // 找出被拖席位所属的玩家与角色（用于冲突判断与逐条定位）
    const payloadRunIds = payload.kind === 'member' ? [payload.runId] : payload.runIds;
    const sourceRun = runs.find((r) => r.id === payloadRunIds[0]);
    const draggedMember = sourceRun?.members.find((m) => m.id === payload.memberId);
    const draggedPlayerId = draggedMember?.playerId ?? null;
    const draggedPlayerName = draggedMember?.playerNickname ?? payload.label;
    const draggedCharacterId = draggedMember?.characterId ?? null;
    if (payload.memberId === targetMemberId) {
      setDragPayload(null);
      return;
    }
    // 落点冲突：被拖角色所属玩家已经在目标车上有别的角色，
    // 换过去会让同一个人在同一趟车上出现两个角色（游戏里不可能这样打）。
    if (targetRun.members.some((m) => m.playerId === draggedPlayerId && m.id !== targetMemberId)) {
      notify(
        '「' +
          draggedPlayerName +
          '」在目标车上已经有角色了，同一个人同一趟车只能上一个角色',
        'error',
      );
      setDragPayload(null);
      return;
    }

    // 同一趟车内拖动暂无意义（不做排序）
    if (payloadRunIds.includes(targetRun.id)) {
      setDragPayload(null);
      return;
    }
    setBusy(true);
    try {
      let result: { source: RunView; target: RunView };
      if (payloadRunIds.length > 1 && draggedCharacterId != null) {
        // 融合车：同一角色在每条车次上都有独立席位，
        // 必须按「同成员 BOSS」逐对换位（后端要求两台车同 BOSS，且按 memberId 定位）
        const targetMember = targetRun.members.find((m) => m.id === targetMemberId);
        const targetCharacterId = targetMember?.characterId ?? null;
        for (const sourceId of payloadRunIds) {
          const sourceSibling = runs.find((r) => r.id === sourceId);
          // 目标卡片里的车次：与 sourceSibling 同 BOSS（后端要求两台车同 BOSS 才能换位）
          const counterpart = runs.find(
            (r) => r.bossId === sourceSibling?.bossId && r.runIndex === targetRun.runIndex,
          );
          if (!counterpart || targetCharacterId == null) {
            continue;
          }
          const sourceMemberId = memberIdOfCharacter(sourceId, draggedCharacterId);
          const targetMemberIdInRun = memberIdOfCharacter(counterpart.id, targetCharacterId);
          if (sourceMemberId != null && targetMemberIdInRun != null) {
            await api.swapMembers(sourceId, sourceMemberId, targetMemberIdInRun);
          }
        }
        await reloadRuns();
        // reloadRuns 之后 state 已是服务端真值，这里给个占位结果避免引用未定义
        result = { source: targetRun, target: targetRun };
      } else {
        result = await api.swapMembers(payloadRunIds[0], payload.memberId, targetMemberId);
        setRuns((prev) =>
          prev.map((run) =>
            run.id === result.source.id ? result.source : run.id === result.target.id ? result.target : run,
          ),
        );
      }
      notify(`已把「${payload.label}」与「${targetLabel}」换位`);
    } catch (error) {
      notify(error instanceof Error ? error.message : '换位失败', 'error');
    } finally {
      setBusy(false);
      setDragPayload(null);
    }
  };


  /**
   * 清理融合车里「对不上主车次」的历史遗留记录。
   * 这 7 个 BOSS 在变成融合车之前是各自独立排班的，趟数互不相干，
   * 残留下来会显示成多出来的空车次（就是之前出现的「第 8 趟 / 第 9 趟」）。
   */
  const cleanupStrayRuns = async () => {
    if (strayRuns.length === 0) {
      return;
    }
    const names = strayRuns
      .slice(0, 8)
      .map((r) => r.bossName + '第' + r.runIndex + '趟')
      .join('、');
    if (
      !window.confirm(
        `发现 ${strayRuns.length} 条对不上主车次的记录（各 BOSS 独立排班时期的历史数据）。\n\n` +
          `将删除：${names}${strayRuns.length > 8 ? ' 等' : ''}\n\n确认清理？`,
      )
    ) {
      return;
    }
    setBusy(true);
    try {
      for (const run of strayRuns) {
        for (const m of run.members) {
          await api.removeMember(run.id, m.id);
        }
        await api.deleteRun(run.id);
      }
      await reloadRuns();
      notify(`已清理 ${strayRuns.length} 条历史遗留记录`);
    } catch (error) {
      notify(error instanceof Error ? error.message : '清理失败', 'error');
    } finally {
      setBusy(false);
    }
  };


  const removeMember = async (run: RunView, memberId: number, label: string, runIds?: number[]) => {
    const ids = runIds && runIds.length > 0 ? runIds : [run.id];
    if (!window.confirm(`把「${label}」从第 ${run.runIndex} 趟车移除？（会回到下方待选池）`)) {
      return;
    }
    try {
      if (ids.length > 1) {
        // 融合车：同一个角色在 7 条车次上各有**自己的席位（memberId 不同）**，
        // 必须按 characterId 在每条车次上分别定位，不能用同一个 memberId 循环。
        const characterId = run.members.find((m) => m.id === memberId)?.characterId;
        if (characterId == null) {
          throw new Error('找不到要移除的席位');
        }
        for (const runId of ids) {
          const targetMemberId = memberIdOfCharacter(runId, characterId);
          if (targetMemberId != null) {
            await api.removeMember(runId, targetMemberId);
          }
        }
        await reloadRuns();
      } else {
        const updated = await api.removeMember(ids[0], memberId);
        setRuns((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      }
      notify('已移除');
    } catch (error) {
      notify(error instanceof Error ? error.message : '移除失败', 'error');
      // 多步操作可能只成功了一部分，失败也要让界面回到服务端真值
      if (ids.length > 1) {
        await reloadRuns().catch(() => undefined);
      }
    }
  };

  const removeRun = async (run: RunView, runIds?: number[]) => {
    const ids = runIds && runIds.length > 0 ? runIds : [run.id];
    const extra = ids.length > 1 ? `（融合车，会同时删除 ${ids.length} 个成员 BOSS 上的这一趟）` : '';
    if (
      !window.confirm(
        `确认删除第 ${run.runIndex} 趟车？${extra}车上的 ${run.memberCount} 个席位会一起移除。`,
      )
    ) {
      return;
    }
    try {
      // 融合车：这一趟在 7 个成员 BOSS 上各有一条记录，必须全部删掉，
      // 否则剩下 6 条会立刻变成「对不上主车次的历史遗留」。
      for (const id of ids) {
        await api.deleteRun(id);
      }
      notify('已删除车次');
      if (currentBoss) {
        loadRuns(viewBossIds);
        loadBase();
      }
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  const createGroup = async () => {
    try {
      await api.createGroup({ name: newGroupName.trim() });
      notify('已创建分组');
      setNewGroupName('');
      loadBase();
    } catch (error) {
      notify(error instanceof Error ? error.message : '创建失败', 'error');
    }
  };

  const removeBoss = async (boss: BossView) => {
    if (!window.confirm(`确认删除 BOSS「${boss.name}」及其 ${boss.runCount} 趟车？`)) {
      return;
    }
    try {
      await api.deleteBoss(boss.id);
      notify('已删除 BOSS');
      if (currentBoss?.id === boss.id) {
        setCurrentBoss(null);
      }
      loadBase();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  /**
   * 「编辑 BOSS」弹窗节点。
   *
   * 为什么要抽成变量：详情视图是**提前 return** 的。如果只把弹窗写在列表视图里，
   * 在详情页点「编辑」时 state 虽然变了，但弹窗根本不在 DOM 里 ——
   * 表现就是「点了没反应」，退回 BOSS 列表才突然弹出来。
   * 所以详情视图和列表视图都要渲染这个节点。
   */
  const bossFormModal = bossForm ? (
    <BossFormModal
      groups={groups}
      initial={bossForm === 'new' ? null : bossForm}
      onClose={() => setBossForm(null)}
      onSaved={() => {
        setBossForm(null);
        loadBase();
      }}
    />
  ) : null;

  // ---------------- BOSS 详情：车次编排 ----------------
  if (currentBoss) {
    const boss = bosses.find((b) => b.id === currentBoss.id) ?? currentBoss;
    return (
      <div>
        <div className="page-head">
          <button className="btn" onClick={() => setCurrentBoss(null)}>
            ← 返回 BOSS 列表
          </button>
          <h1>
            {currentCombo.length === 0 ? (
              <BossIcon iconKey={boss.iconKey} name={boss.name} size={30} />
            ) : null}
            {boss.name} · 排班
          </h1>
          <span className={tierClass(boss.tier)}>{tierText(boss.tier, boss.tierLabel)}</span>
          <span className="sub">
            每车 {boss.capacity} 人 · {tierText(selectedTier)} · 共 {runCards.length} 趟
            {currentCombo.length > 0 ? ' · 要打全打' : ''}
          </span>
          <div className="spacer" />
          <button className="btn" onClick={() => loadRuns(viewBossIds)}>
            刷新
          </button>
          {currentCombo.length === 0 ? (
            <>
              <button className="btn" onClick={() => setBossForm(boss)}>
                编辑
              </button>
              <button className="btn danger" onClick={() => removeBoss(boss)}>
                删除
              </button>
            </>
          ) : null}
          <button
            className="btn primary"
            onClick={() => setRunForm({ bossId: primaryBossId, bossName: boss.name, tier: selectedTier })}
          >
            + 新增车次
          </button>
        </div>

        {/* 融合车数据自检：各 BOSS 独立排班时期残留的车次会造成多出来的空车次 */}
        {strayRuns.length > 0 ? (
          <div className="voice-warn" style={{ marginBottom: 14 }}>
            发现 <strong>{strayRuns.length} 条对不上主车次的历史记录</strong>：
            {strayRuns.slice(0, 8).map((r) => (
              <span key={r.id}>
                {" "}
                {r.bossName}第{r.runIndex}趟
              </span>
            ))}
            {strayRuns.length > 8 ? <span> 等</span> : null}
            <div style={{ marginTop: 8 }}>
              <button
                className="btn small danger"
                disabled={busy}
                onClick={() => void cleanupStrayRuns()}
              >
                一键清理这些历史车次
              </button>
            </div>
          </div>
        ) : null}

        {/* 第 2 步：选难度（融合车固定难度，不需要选） */}
        <div className="step-block" hidden={currentCombo.length > 0}>
          <div className="step-title">
            <span className="step-no">1</span> 选择难度
            <span className="muted">这个 BOSS 有的难度（数字是趟数）</span>
          </div>
          <div className="tier-tabs">
            {(availableTiers.length > 0 ? availableTiers : [boss.tier]).map((tier) => (
              <button
                key={tier}
                type="button"
                className={`tier-tab ${selectedTier === tier ? 'active' : ''}`}
                onClick={() => setSelectedTier(tier)}
              >
                {tierText(tier)}
                <span className="tier-count">{runs.filter((run) => run.tier === tier).length} 趟</span>
              </button>
            ))}
            {availableTiers.length === 0 ? (
              <span className="muted">这个 BOSS 还没有车次，用右上角「+ 新增车次」开始排</span>
            ) : null}
          </div>
        </div>

        {/* 第 2 步：排班 */}
        <div className="step-block">
          <div className="step-title">
            <span className="step-no">2</span> 排班
            <span className="muted">拖动席位换车，或从左下角色池拖进来</span>
          </div>
        </div>

        <div className="card" style={{ marginBottom: 20 }}>
          <div className="card-title">
            待选角色池
            <span className="muted">把角色拖到下面的车次里即可上车（已排进该 BOSS 的角色不再显示）</span>
            <div className="spacer" />
            <select
              value={poolPlayer}
              onChange={(e) => setPoolPlayer(e.target.value)}
              style={{ width: 170 }}
              title="按群友筛选，方便快速找到要上车的角色"
            >
              <option value="">全部群友</option>
              {poolPlayers.map((name) => (
                <option key={name} value={name}>
                  {name}
                </option>
              ))}
            </select>
          </div>
          <div
            className="char-pool"
            onDragOver={(e) => e.preventDefault()}
            title="也可以从车次上把席位拖回这里 = 移除"
          >
            {visiblePoolCharacters.map((character) => (
              <span
                key={character.id}
                className={`member-chip draggable${touch && touchSelected?.characterId === character.id ? ' touch-selected' : ''}`}
                title={memberTooltip({
                  playerNickname: character.playerNickname,
                  job: character.job,
                  power: character.power,
                  characterNote: character.note,
                  mainChar: character.mainChar,
                })}
                draggable
                onClick={
                  touch
                    ? (e: React.MouseEvent) => {
                        e.stopPropagation();
                        if (touchSelected?.characterId === character.id) {
                          setTouchSelected(null);
                          return;
                        }
                        if (!touchSelected) {
                          setTouchSelected({
                            kind: 'character',
                            characterId: character.id,
                            playerId: character.playerId,
                            label: `${character.playerNickname} ${character.job}`,
                          });
                          notify(`已选中「${character.playerNickname} ${character.job}」，再点一趟车即可上车`);
                          return;
                        }
                        // 已选中某个席位 → 与这个待选角色互换（先移除再上车）
                        void applyTouchTarget(null, [], 0, character.id, `${character.playerNickname} ${character.job}`);
                      }
                    : undefined
                }
                onDragStart={(e) => {
                  e.dataTransfer.effectAllowed = 'move';
                  setDragPayload({
                    kind: 'character',
                    characterId: character.id,
                    label: `${character.playerNickname} ${character.job}`,
                  });
                }}
                onDragEnd={() => setDragPayload(null)}
              >
                <strong>{character.playerNickname}</strong> {character.job}
                <span className="muted"> {character.power}M</span>
              </span>
            ))}
            {poolCharacters.length === 0 ? (
              <span className="muted">全部都排进车次了</span>
            ) : null}
            {poolCharacters.length > 0 && visiblePoolCharacters.length === 0 ? (
              <span className="muted">
                「{poolPlayer}」的角色都已经排进车次了，换个群友看看
              </span>
            ) : null}
          </div>
        </div>

        <div className={currentCombo.length > 0 ? 'runs-row combo-runs-row' : 'runs-row flex-start-row'}>
          {runCards.map((card) => {
            // 融合车卡片：阵容取并集，其余按主车次显示
            const run = { ...card.primary, members: card.members, memberCount: card.members.length };
            const full = run.memberCount >= run.capacity;
            return (
              <div
                key={card.key}
                className={`run-tile run-drop ${card.isCombo ? 'combo-tile' : ''} ${dragOverRun === run.id ? 'drag-over' : ''} ${
                  run.status === 'DONE' ? 'is-done' : ''
                }`}
                onDragEnter={(e) => {
                  e.preventDefault();
                  setDragOverRun(run.id);
                }}
                onDragOver={(e) => {
                  e.preventDefault();
                  e.dataTransfer.dropEffect = 'move';
                  if (dragOverRun !== run.id) {
                    setDragOverRun(run.id);
                  }
                }}
                onDragLeave={(e) => {
                  // 只有真正离开这张卡片时才取消高亮：
                  // 移到卡片内部的子元素（成员/空位）也会触发 dragleave，不能当作出去了
                  if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
                    setDragOverRun((prev) => (prev === run.id ? null : prev));
                  }
                }}
                onDrop={(e) => handleDrop(e, run, card.runIds)}
                // 手机上点这趟车：已选中的角色会直接上车（点空位）或执行调车
                // 注意不阻止冒泡到席位：席位的 onClick 会 stopPropagation
                onClick={
                  touch
                    ? () => {
                        if (!touchSelected) {
                          return;
                        }
                        if (touchSelected.kind === 'character') {
                          void applyTouchTarget(run, card.runIds, 0, 0, touchSelected.label);
                        } else {
                          void applyTouchTarget(run, card.runIds, 0, 0, touchSelected.label);
                        }
                      }
                    : undefined
                }
              >
                {card.isCombo ? (
                  // 融合车：把 7 个 BOSS 的图标与名称都平铺出来，一眼看清这趟车打哪几个
                  <div className="combo-run-head">
                    {currentCombo.map((m) => (
                      <div className="combo-head-icon" key={m.id} title={m.name}>
                        <BossIcon iconKey={m.iconKey} name={m.name} size={26} />
                        <span className="nm">{m.name}</span>
                      </div>
                    ))}
                  </div>
                ) : null}
                <header className="tile-head">
                  <span className="tile-boss">第 {card.runIndex} 趟</span>
                  <span className={`tag status-${run.status}`}>{statusLabel(run.status)}</span>
                  <div className="spacer" />
                  <span className={full ? 'tag tier-H' : 'tag'}>
                    {run.memberCount}/{run.capacity}
                  </span>
                </header>
                {run.scheduled ? <div className="tile-time">🕘 {run.scheduled}</div> : null}

                {/*
                  换位落点冲突判断：如果被拖角色所属玩家在「目标车」上已经有别的角色，
                  那么换位会让同一个人在同一趟车上出现两个角色 —— 游戏里不可能这样打，
                  所以这些落点标记成冲突并禁止放下。
                */}
                <div className="slot-list">
                  {run.members.map((member) => {
                    // 被拖角色的玩家，在目标车上已经有别的角色 → 换位会违规
                    const swapConflict =
                      dragPlayerId != null &&
                      drag != null &&
                      (drag.kind === 'member' || drag.kind === 'combo-member') &&
                      member.playerId === dragPlayerId;
                    return (
                    <div
                      key={member.id}
                      className={`slot-item draggable${dragOverMemberId === member.id ? (swapConflict ? ' swap-conflict' : ' swap-target') : ''}${touchSelected?.memberId === member.id ? ' touch-selected' : ''}${touch && touchSelected && touchSelected.memberId !== member.id ? ' touch-target' : ''}`}
                      // 手机上点一下选中席位，再点目标车次/席位即可调车或换位
                      onClick={
                        touch
                          ? (e: React.MouseEvent) => {
                              // 阻止冒泡到车次卡片的 onClick，否则会执行两次
                              e.stopPropagation();
                              if (touchSelected?.memberId === member.id) {
                                setTouchSelected(null);
                                return;
                              }
                              if (!touchSelected) {
                                setTouchSelected({
                                  kind: card.isCombo ? 'combo-member' : 'member',
                                  memberId: member.id,
                                  runId: run.id,
                                  runIds: card.runIds,
                                  characterId: member.characterId,
                                  playerId: member.playerId,
                                  label: `${member.playerNickname} ${member.job}`,
                                });
                                notify(`已选中「${member.playerNickname} ${member.job}」，再点一趟车即可调过去`);
                                return;
                              }
                              // 已选中别的席位 → 执行换位 / 调车
                              void applyTouchTarget(run, card.runIds, member.id, member.characterId, `${member.playerNickname} ${member.job}`);
                            }
                          : undefined
                      }
                      draggable
                      onDragStart={(e) => {
                        e.dataTransfer.effectAllowed = 'move';
                        // 融合车：这个席位在 7 条车次上都有记录，拖拽要带上全部
                        setDragPayload(
                          card.isCombo
                            ? {
                                kind: 'combo-member',
                                memberId: member.id,
                                runIds: card.runIds,
                                label: `${member.playerNickname} ${member.job}`,
                              }
                            : {
                                kind: 'member',
                                memberId: member.id,
                                runId: run.id,
                                label: `${member.playerNickname} ${member.job}`,
                              },
                        );
                        setDragPlayerId(member.playerId);
                      }}
                      onDragEnd={() => {
                        setDragPayload(null);
                        setDragOverMemberId(null);
                        setDragPlayerId(null);
                      }}
                      onDragOver={(e) => {
                        // 拖到已有成员身上 → 准备换位
                        e.preventDefault();
                        e.stopPropagation();
                        e.dataTransfer.dropEffect = 'move';
                        const dragKind = dragRef.current?.kind;
                        if ((dragKind === 'member' || dragKind === 'combo-member') && dragOverMemberId !== member.id) {
                          setDragOverMemberId(member.id);
                        }
                      }}
                      onDragLeave={() => {
                        setDragOverMemberId((prev) => (prev === member.id ? null : prev));
                      }}
                      onDrop={(e) =>
                        handleSwap(
                          e,
                          run,
                          member.id,
                          `${member.playerNickname} ${member.job}`,
                        )
                      }
                      title={`${memberTooltip(member)}\n\n拖到别的车次 = 调车；拖到另一个角色身上 = 两人换位`}
                    >
                      <span className="nm">{member.playerNickname}</span>
                      <span className="jb">{member.job}</span>
                      <span className="pw">{member.power}M</span>
                      {member.status !== 'CONFIRMED' ? (
                        <span className="tag">{memberStatusLabel(member.status)}</span>
                      ) : null}
                      <button
                        className="x"
                        title="移出这趟车（回到待选池）"
                        onClick={() =>
                          removeMember(
                            run,
                            member.id,
                            `${member.playerNickname} ${member.job}`,
                            card.runIds,
                          )
                        }
                      >
                        ×
                      </button>
                    </div>
                    );
                  })}
                  {Array.from({ length: Math.max(0, run.capacity - run.memberCount) }).map((_, index) => (
                    <div className="slot-item empty" key={`empty-${index}`}>
                      {drag ? '松开鼠标放到这里' : `空位 ${run.memberCount + index + 1}`}
                    </div>
                  ))}
                </div>

                <footer className="tile-foot">
                  <span className="tile-stat muted">
                    {run.memberCount}/{run.capacity} 人 · 总战力 {run.powerTotal}M
                  </span>
                  <div className="tile-actions">
                    <button
                      className="btn small"
                      onClick={() => setRunForm({ bossId: primaryBossId, bossName: boss.name, run })}
                    >
                      设置
                    </button>
                    <button className="btn small danger" onClick={() => removeRun(run, card.runIds)}>
                      删除
                    </button>
                  </div>
                </footer>
              </div>
            );
          })}
        </div>

        {visibleRuns.length === 0 && !loading ? (
          <p className="muted">
            这个 BOSS 在「{tierText(selectedTier)}」还没有车次，点右上角「+ 新增车次」开始排。
          </p>
        ) : null}

        {runForm ? (
          <RunFormModal
            bossId={runForm.bossId}
            bossName={runForm.bossName}
            defaultTier={runForm.tier}
            initial={runForm.run ?? null}
            onClose={() => setRunForm(null)}
            onSaved={() => {
              setRunForm(null);
              // 融合车要重新拉全部成员 BOSS 的车次
              void reloadRuns();
              loadBase();
            }}
          />
        ) : null}

        {/* 详情页点「编辑」也要立即弹出 —— 与列表视图共用同一个节点 */}
        {bossFormModal}
      </div>
    );
  }

  // ---------------- BOSS 列表 ----------------
  return (
    <div>
      <div className="page-head">
        <h1>boss排班</h1>
        <span className="sub">
          仅管理员可见 · 共 {groups.length} 个分组、{bosses.length} 个 BOSS · 点开 BOSS 查看并调整它的车次
        </span>
        <div className="spacer" />
        <button className="btn primary" onClick={() => setBossForm('new')}>
          + 新增 BOSS
        </button>
      </div>

      {groupedDisplay.map(([groupName, list]) => (
        <section key={groupName} className="group-section">
          <h2 className="group-title">
            {groupName}
            <span className="muted">{list.length} 个 BOSS</span>
          </h2>
          <div className="boss-grid flex-start-grid">
            {list.map((boss) => {
              // 融合车：一个长条大框，里面平铺全部成员 BOSS 的图标与名称
              if (boss.id < 0) {
                const members = comboBossesOf(boss.comboKey);
                return (
                  <article
                    className="boss-card boss-card-combo clickable"
                    key={boss.id}
                    onClick={() => openBoss(boss)}
                    title={`${boss.name}：${members.map((m) => m.name).join(' → ')}`}
                  >
                    <div className="combo-head">
                      <span className="combo-title">{boss.name}</span>
                      <span className="tag tier-H">固定 {tierText(boss.comboTier ?? 'H')}</span>
                      <span className="muted">要打全打 · 一共 {members.length} 个 BOSS</span>
                    </div>
                    <div className="combo-bosses">
                      {members.map((m) => (
                        <div className="combo-boss" key={m.id} title={m.name}>
                          <BossIcon iconKey={m.iconKey} name={m.name} size={58} />
                          <span className="combo-boss-name">{m.name}</span>
                        </div>
                      ))}
                    </div>
                  </article>
                );
              }
              return (
                <article
                  className="boss-card clickable slim"
                  key={boss.id}
                  onClick={() => openBoss(boss)}
                  title={`点开「${boss.name}」选难度排班`}
                >
                  <div className="boss-card-icon">
                    <BossIcon iconKey={boss.iconKey} name={boss.name} size={96} />
                  </div>
                  <div className="boss-card-name" title={boss.name}>
                    {boss.name}
                  </div>
                </article>
              );
            })}
          </div>
        </section>
      ))}

      {grouped.length === 0 && !loading ? <p className="muted">暂无 BOSS，请先新增。</p> : null}
      {loading ? <p className="muted">加载中…</p> : null}

      <div className="card">
        <div className="card-title">分组</div>
        <div className="chips" style={{ marginBottom: 14 }}>
          {groups.map((group) => (
            <span className="chip" key={group.id}>
              {group.name}
              <span className="muted"> {group.bossCount} 个 BOSS</span>
            </span>
          ))}
        </div>
        <div style={{ display: 'flex', gap: 10, alignItems: 'flex-end', maxWidth: 460 }}>
          <label className="field" style={{ flex: 1, marginBottom: 0 }}>
            <span>新增分组名称</span>
            <input
              type="text"
              value={newGroupName}
              onChange={(e) => setNewGroupName(e.target.value)}
              placeholder="例如 神兽"
            />
          </label>
          <button className="btn" onClick={createGroup} disabled={!newGroupName.trim()}>
            添加分组
          </button>
        </div>
      </div>

      {bossFormModal}
    </div>
  );
}

function BossFormModal({
  groups,
  initial,
  onClose,
  onSaved,
}: {
  groups: GroupView[];
  initial: BossView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { notify } = useToast();
  const [groupId, setGroupId] = useState<number>(initial?.groupId ?? groups[0]?.id ?? 0);
  const [name, setName] = useState(initial?.name ?? '');
  const [tier, setTier] = useState(initial?.tier ?? 'NONE');
  const [tiers, setTiers] = useState<string[]>(initial?.tiers ?? []);

  /**
   * 游戏里实际存在的难度。
   *
   * 后端 BossTierCatalog 给的事实，例如「监视者卡尔洛斯」没有 Hard。
   * 不在这个范围内的难度**禁用勾选**，避免存进游戏里根本不存在的组合；
   * 空数组表示这个 BOSS 还没登记，此时不限制（方便先排班再补登记）。
   */
  const allowedTiers = initial?.allowedTiers ?? [];
  const tierAllowed = (value: string) => allowedTiers.length === 0 || allowedTiers.includes(value);
  const [capacity, setCapacity] = useState(String(initial?.capacity ?? 6));
  const [iconKey, setIconKey] = useState(initial?.iconKey ?? '');
  const [note, setNote] = useState(initial?.note ?? '');
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const body = {
        groupId,
        name: name.trim(),
        tier,
        tiers: TIER_ORDER.filter((t) => tiers.includes(t) || t === tier),
        capacity: Number(capacity) || 6,
        enabled: true,
        iconKey: iconKey.trim(),
        note,
      };
      if (initial) {
        await api.updateBoss(initial.id, body);
      } else {
        await api.createBoss(body);
      }
      notify('已保存 BOSS');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      title={initial ? `编辑 BOSS：${initial.name}` : '新增 BOSS'}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="boss-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="boss-form" onSubmit={submit}>
        <div style={{ display: 'flex', gap: 18, alignItems: 'flex-start' }}>
          <div style={{ textAlign: 'center' }}>
            <BossIcon iconKey={iconKey || initial?.iconKey} name={name} size={96} />
            <div className="muted" style={{ fontSize: 12, marginTop: 6 }}>
              头像预览
            </div>
          </div>
          <div style={{ flex: 1 }}>
            <label className="field">
              <span>所属分组</span>
              <select value={groupId} onChange={(e) => setGroupId(Number(e.target.value))} required>
                {groups.map((group) => (
                  <option key={group.id} value={group.id}>
                    {group.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="field">
              <span>BOSS 名称</span>
              <input type="text" value={name} onChange={(e) => setName(e.target.value)} required />
            </label>
            <label className="field" style={{ marginBottom: 0 }}>
              <span>头像图标名（/boss-icons/ 下的文件名，不含 .png）</span>
              <input
                type="text"
                value={iconKey ?? ''}
                onChange={(e) => setIconKey(e.target.value)}
                placeholder="例如 seren"
              />
            </label>
          </div>
        </div>

        <div className="grid" style={{ gridTemplateColumns: '1fr 1fr', marginTop: 18, gap: 16 }}>
          <label className="field" style={{ marginBottom: 0 }}>
            <span>默认难度</span>
            <select value={tier} onChange={(e) => setTier(e.target.value)}>
              {TIER_OPTIONS.filter((item) => tierAllowed(item.value)).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field" style={{ marginBottom: 0 }}>
            <span>每趟车人数上限</span>
            <input
              type="number"
              min={1}
              max={12}
              value={capacity}
              onChange={(e) => setCapacity(e.target.value)}
            />
          </label>
        </div>

        <div className="field" style={{ marginTop: 16 }}>
          <span>这个 BOSS 实际存在的难度（排班页只显示勾选的难度）</span>
          <div className="tier-checks">
            {TIER_OPTIONS.filter((item) => item.value !== 'NONE').map((item) => (
              <label
                key={item.value}
                className={`tier-check ${tiers.includes(item.value) ? 'on' : ''} ${tierAllowed(item.value) ? '' : 'off'}`}
                title={tierAllowed(item.value) ? '' : `游戏里没有「${item.label}」难度`}
              >
                <input
                  type="checkbox"
                  checked={tiers.includes(item.value)}
                  disabled={!tierAllowed(item.value)}
                  onChange={(e) =>
                    setTiers((prev) =>
                      e.target.checked
                        ? TIER_ORDER.filter((t) => prev.includes(t) || t === item.value)
                        : prev.filter((t) => t !== item.value)
                    )
                  }
                />
                {item.label}
                {tierAllowed(item.value) ? null : <span className="tier-none">（游戏无此难度）</span>}
              </label>
            ))}
          </div>
        </div>

        <label className="field" style={{ marginBottom: 0 }}>
          <span>备注</span>
          <input type="text" value={note ?? ''} onChange={(e) => setNote(e.target.value)} />
        </label>
      </form>
    </Modal>
  );
}
