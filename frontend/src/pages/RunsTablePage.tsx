import { useEffect, useMemo, useState } from 'react';
import { api } from '../api/client';
import type { BossView, RunView, WeekDetail } from '../api/types';
import { Link } from 'react-router-dom';
import { useAuth } from '../components/AuthContext';
import { useDevice } from '../components/DeviceContext';
import { useToast } from '../components/Toast';
import { memberStatusLabel, memberTooltip, statusLabel, tierClass, tierText } from '../components/ui';
import BossIcon from '../components/BossIcon';
import { groupBossesForDisplay } from '../features/combo';

/** 车次一览表：按 BOSS 汇总成表格，方便查阅；管理员/编辑可直接在此编排。 */
export default function RunsTablePage() {
  const { user, canEdit, canSchedule } = useAuth();
  /** 手机版：每个 BOSS 的每一趟车拆成一张卡片，竖向滚动查看 */
  const { isMobile } = useDevice();
  const { notify } = useToast();
  const [runs, setRuns] = useState<RunView[]>([]);
  const [bosses, setBosses] = useState<BossView[]>([]);
  const [groupFilter, setGroupFilter] = useState('');
  const [bossFilter, setBossFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [loading, setLoading] = useState(true);
  const [myRunIds, setMyRunIds] = useState<number[]>([]);
  const [weeks, setWeeks] = useState<WeekDetail | null>(null);
  const [weekKey, setWeekKey] = useState<string>('');

  const load = (key?: string) => {
    setLoading(true);
    Promise.all([key ? api.runsOfWeek(key) : api.runs(), api.bosses()])
      .then(([runList, bossList]) => {
        setRuns(runList);
        setBosses(bossList);
      })
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    api
      .weekTriples()
      .then((detail) => {
        setWeeks(detail);
        setWeekKey(detail.currentWeekKey);
        load(detail.currentWeekKey);
      })
      .catch(() => load());
  }, []);

  useEffect(() => {
    if (!user?.playerId) {
      setMyRunIds([]);
      return;
    }
    api
      .playerRuns(user.playerId)
      .then((list) => setMyRunIds(list.map((item) => item.runId)))
      .catch(() => setMyRunIds([]));
  }, [user?.playerId]);

  const myRuns = useMemo(() => new Set(myRunIds), [myRunIds]);

  /**
   * 把融合车的多个 BOSS 合并成一个展示条目。
   *
   * 融合车在数据层是「每个成员 BOSS 各一条 boss_run」，这里合成一行来展示：
   * 图标与名称平铺全部成员 BOSS，趟数以主 BOSS（第一个成员）为准，
   * 避免把 7 个 BOSS 各自的历史趟数当成 7 倍车次。
   */
  /**
   * 列表要展示的条目：融合车合并成一个虚拟条目（共享逻辑，见 features/combo.ts）。
   * 之前这里是各页面自己写的一份，导致口径漂移，已统一。
   */
  const displayBosses = useMemo(() => groupBossesForDisplay(bosses), [bosses]);

  const blocks = useMemo(() => {
    const text = keyword.trim().toLowerCase();
    const result: Array<{ boss: BossView; runs: RunView[]; members: BossView[] }> = [];
    for (const { boss, members, primaryBossId } of displayBosses) {
      if (groupFilter && boss.groupName !== groupFilter) {
        continue;
      }
      if (bossFilter && String(boss.id) !== bossFilter) {
        continue;
      }
      const bossRuns = runs
        .filter((run) => run.bossId === primaryBossId)
        .filter((run) => {
          if (!text) {
            return true;
          }
          // 融合车：搜索任一成员 BOSS 的名字也能命中
          if (members.some((m) => m.name.toLowerCase().includes(text) || m.name.includes(keyword.trim()))) {
            return true;
          }
          if (boss.name.toLowerCase().includes(text) || boss.name.includes(keyword.trim())) {
            return true;
          }
          return run.members.some(
            (member) =>
              member.playerNickname.toLowerCase().includes(text) || member.job.toLowerCase().includes(text),
          );
        });
      if (bossRuns.length > 0) {
        result.push({ boss, runs: bossRuns, members });
      }
    }
    return result;
  }, [displayBosses, runs, groupFilter, bossFilter, keyword]);

  const groups = useMemo(() => Array.from(new Set(bosses.map((boss) => boss.groupName))), [bosses]);

  /** 当前分组下的 BOSS（用于第二个下拉框）：融合车合并成一个条目 */
  const bossOptions = useMemo(
    () =>
      displayBosses
        .filter(({ boss }) => !groupFilter || boss.groupName === groupFilter)
        .map(({ boss }) => boss),
    [displayBosses, groupFilter],
  );

  const markStatus = async (run: RunView, status: 'DONE' | 'PLANNED') => {
    try {
      const updated = await api.setRunStatus(run.id, status);
      setRuns((prev) => prev.map((item) => (item.id === updated.id ? updated : item)));
      notify(status === 'DONE' ? '已标记完成' : '已改回未完成');
    } catch (error) {
      notify(error instanceof Error ? error.message : '标记失败', 'error');
    }
  };

  return (
    <div>
      <div className="page-head">
        <h1>车次一览表</h1>
        <span className="sub">按 BOSS 汇总，方便查阅；车上成员可以标记自己车的攻坚状态</span>
      </div>

      <div className={`toolbar${isMobile ? ' toolbar-sticky' : ''}`}>
        {weeks ? (
          <select value={weekKey} onChange={(e) => { setWeekKey(e.target.value); load(e.target.value); }} style={{ width: 200 }}>
            <option value={weeks.lastWeek.weekKey}>上周（{weeks.lastWeek.label}）</option>
            <option value={weeks.thisWeek.weekKey}>本周（{weeks.thisWeek.label}）</option>
            <option value={weeks.nextWeek.weekKey}>下周（{weeks.nextWeek.label}）</option>
          </select>
        ) : null}
        <select
          value={groupFilter}
          onChange={(e) => {
            setGroupFilter(e.target.value);
            // 换分组后原来选的 BOSS 可能不在这个分组里，重置为「全部」
            setBossFilter('');
          }}
          style={{ width: 160 }}
        >
          <option value="">全部分组</option>
          {groups.map((group) => (
            <option key={group} value={group}>
              {group}
            </option>
          ))}
        </select>
        <select value={bossFilter} onChange={(e) => setBossFilter(e.target.value)} style={{ width: 190 }}>
          <option value="">全部 BOSS</option>
          {bossOptions.map((boss) => (
            <option key={boss.id} value={String(boss.id)}>
              {boss.name}
            </option>
          ))}
        </select>
        <input
          type="text"
          placeholder="搜索 BOSS / 群友 / 职业…"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          style={{ maxWidth: 260 }}
        />
        <button className="btn" onClick={() => load(weekKey)}>
          刷新
        </button>
        {canSchedule ? (
          <Link className="btn" to="/bosses">
            🐉 boss排班
          </Link>
        ) : null}
        {loading ? <span className="muted">加载中…</span> : null}
      </div>

      {blocks.map(({ boss, runs: bossRuns, members }) => (
        <div className="card" key={boss.id}>
          <div className="card-title">
            {members.length > 0 ? (
              // 融合车：平铺全部成员 BOSS 的图标与名称
              <span className="combo-title-icons">
                {members.map((m) => (
                  <span className="combo-title-icon" key={m.id} title={m.name}>
                    <BossIcon iconKey={m.iconKey} name={m.name} size={24} />
                    <span className="nm">{m.name}</span>
                  </span>
                ))}
              </span>
            ) : (
              <BossIcon iconKey={boss.iconKey} name={boss.name} size={28} />
            )}
            {boss.name}
            <span className="muted">
              {boss.groupName} · 每车 {boss.capacity} 人 · {bossRuns.length} 趟
              {members.length > 0 ? ' · 要打全打' : ''}
            </span>
          </div>
          {isMobile ? (
            /* ============ 手机版：一趟车一张卡片，竖向排列 ============ */
            <div className="run-cards-mobile">
              {bossRuns.map((run) => {
                const mine = myRuns.has(run.id);
                return (
                  <article className={`run-card-mobile${mine ? ' is-mine' : ''}`} key={run.id}>
                    <header className="rc-head">
                      <span className="rc-index">第 {run.runIndex} 趟</span>
                      <span className={tierClass(run.tier)}>{tierText(run.tier, run.tierLabel)}</span>
                      <div className="spacer" />
                      {mine ? <span className="tag mine-tag">我参加</span> : null}
                      <span className={`tag status-${run.status}`}>{statusLabel(run.status)}</span>
                    </header>

                    <div className="rc-members">
                      {run.members.map((member) => (
                        <span className="member-chip" key={member.id} title={memberTooltip(member)}>
                          <strong>{member.playerNickname}</strong> {member.job}
                          {member.mainChar ? <span className="tag main-tag">主号</span> : null}
                          <span className="muted"> {member.power}M</span>
                          {member.status !== 'CONFIRMED' ? (
                            <span className="tag">{memberStatusLabel(member.status)}</span>
                          ) : null}
                        </span>
                      ))}
                      {run.memberCount === 0 ? <span className="muted">还没人上车</span> : null}
                    </div>

                    <footer className="rc-foot">
                      <span className="muted">
                        {run.memberCount}/{run.capacity} 人 · 总战力 {run.powerTotal}M
                        {run.scheduled ? ` · ${run.scheduled}` : ''}
                      </span>
                      <div className="spacer" />
                      {canEdit || mine ? (
                        run.status === 'DONE' ? (
                          <button className="btn small" onClick={() => markStatus(run, 'PLANNED')}>
                            改回未完成
                          </button>
                        ) : (
                          <button className="btn small primary" onClick={() => markStatus(run, 'DONE')}>
                            {canEdit ? '✓ 已完成' : '✓ 我打完了'}
                          </button>
                        )
                      ) : null}
                    </footer>
                  </article>
                );
              })}
            </div>
          ) : (
          <div className="table-scroll">
            <table>
              <thead>
                <tr>
                  <th style={{ width: 70 }}>难度</th>
                  <th>成员</th>
                  <th style={{ width: 76, textAlign: 'center' }}>我参加</th>
                  <th style={{ width: 110 }}>人数</th>
                  <th style={{ width: 130 }}>总战力（M）</th>
                  <th style={{ width: 110 }}>状态</th>
                  <th style={{ width: 130 }}>操作</th>
                </tr>
              </thead>
              <tbody>
                {bossRuns.map((run) => {
                  const mine = myRuns.has(run.id);
                  return (
                    <tr key={run.id} className={mine ? 'row-mine' : ''}>
                      <td>
                        <span className={tierClass(run.tier)}>{tierText(run.tier, run.tierLabel)}</span>
                      </td>
                      <td>
                        <div className="member-inline">
                          {run.members.map((member) => (
                            <span className="member-chip" key={member.id} title={memberTooltip(member)}>
                              <strong>{member.playerNickname}</strong> {member.job}
                              {member.mainChar ? <span className="tag main-tag">主号</span> : null}
                              <span className="muted"> {member.power}M</span>
                              {member.status !== 'CONFIRMED' ? (
                                <span className="tag">{memberStatusLabel(member.status)}</span>
                              ) : null}
                            </span>
                          ))}
                          {run.memberCount === 0 ? <span className="muted">还没人上车</span> : null}
                        </div>
                      </td>
                      <td style={{ textAlign: 'center' }}>
                        {mine ? <span className="tag mine-tag">是</span> : null}
                      </td>
                      <td>
                        {run.memberCount}/{run.capacity}
                      </td>
                      <td>{run.powerTotal}M</td>
                      <td>
                        <span className={`tag status-${run.status}`}>{statusLabel(run.status)}</span>
                        {run.scheduled ? <div className="muted">{run.scheduled}</div> : null}
                      </td>
                      <td>
                        <div className="row-actions">
                          {canEdit || mine ? (
                            run.status === 'DONE' ? (
                              <button className="btn small" onClick={() => markStatus(run, 'PLANNED')}>
                                改回未完成
                              </button>
                            ) : (
                              <button className="btn small primary" onClick={() => markStatus(run, 'DONE')}>
                                {canEdit ? '✓ 已完成' : '✓ 我打完了'}
                              </button>
                            )
                          ) : (
                            <span className="muted">—</span>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          )}
        </div>
      ))}

      {blocks.length === 0 && !loading ? <p className="muted">没有匹配的车次。</p> : null}

    </div>
  );
}


