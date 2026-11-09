import { Fragment, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api/client';
import type { PlayerRunView, PlayerView, RunView } from '../api/types';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';
import { memberTooltip, statusLabel, tierClass, tierText } from '../components/ui';
import { mergeMyRuns, type MergedPlayerRun } from '../features/combo';
import type { BossView } from '../api/types';

/** 我的车次：群友登录后在这里认领自己的群友身份、标记自己车的状态。 */
export default function MyRunsPage() {
  const { user } = useAuth();
  const { notify } = useToast();
  const [players, setPlayers] = useState<PlayerView[]>([]);
  const [myRuns, setMyRuns] = useState<PlayerRunView[]>([]);
  const [runs, setRuns] = useState<RunView[]>([]);
  const [loading, setLoading] = useState(true);
  /** 全部 BOSS（判断哪些属于融合车） */
  const [bosses, setBosses] = useState<BossView[]>([]);

  const load = () => {
    if (!user?.playerId) {
      setMyRuns([]);
      setLoading(false);
      return;
    }
    setLoading(true);
    Promise.all([api.playerRuns(user.playerId), api.runs(), api.bosses()])
      .then(([mine, all, bossList]) => {
        setMyRuns(mine);
        setRuns(all);
        setBosses(bossList);
      })
      .catch((error) => notify(error instanceof Error ? error.message : '加载失败', 'error'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, [user?.playerId]);

  useEffect(() => {
    api
      .players()
      .then(setPlayers)
      .catch(() => setPlayers([]));
  }, []);

  const runById = useMemo(() => new Map(runs.map((run) => [run.id, run])), [runs]);

;

  /**
   * 标记状态。
   * 融合车是「要打全打」，所以标记某一趟时要**同步更新该趟在全部成员 BOSS 上的记录** ——
   * 否则群友得为同一趟车连点 7 次「已完成」，而且只点一次时其它页面还显示未完成。
   */
  const mark = async (runIds: number[], status: 'DONE' | 'PLANNED') => {
    try {
      const updatedStatuses: Array<{ id: number; status: PlayerRunView['status'] }> = [];
      for (const runId of runIds) {
        const updated = await api.setRunStatus(runId, status);
        updatedStatuses.push({ id: updated.id, status: updated.status });
      }
      const byId = new Map(updatedStatuses.map((x) => [x.id, x.status]));
      setRuns((prev) => prev.map((item) => (byId.has(item.id) ? { ...item, status: byId.get(item.id)! } : item)));
      setMyRuns((prev) =>
        prev.map((item) => (byId.has(item.runId) ? { ...item, status: byId.get(item.runId)! } : item)),
      );
      notify(status === 'DONE' ? '整趟车都标记完成啦，辛苦！' : '已改回未完成');
    } catch (error) {
      notify(error instanceof Error ? error.message : '标记失败', 'error');
      load();
    }
  };

  /** 融合车合并后的展示项：一趟车只出一条，不再按成员 BOSS 拆成 7 条 */
  const mergedRuns = useMemo<MergedPlayerRun[]>(
    () => mergeMyRuns(myRuns, runs, bosses),
    [myRuns, runs, bosses],
  );
  /** 当前账号关联的群友（只读展示用） */
  const myPlayer = useMemo(
    () => (user?.playerId ? players.find((x) => x.id === user.playerId) ?? null : null),
    [players, user?.playerId],
  );

  /** 按角色筛选（characterId，空串 = 全部） */
  const [charFilter, setCharFilter] = useState('');
  /** 按 BOSS 筛选（bossId，空串 = 全部） */
  const [bossFilter, setBossFilter] = useState('');

  /**
   * 角色下拉选项：只从「我真的排到的车」里提取。
   * 用 characterId 区分 —— 同一玩家可能有同职业的两个角色，只按职业名会混。
   */
  const charOptions = useMemo(() => {
    const map = new Map<number, { id: number; label: string; count: number }>();
    for (const run of mergedRuns) {
      const id = run.characterId;
      if (id == null) {
        continue;
      }
      const hit = map.get(id);
      if (hit) {
        hit.count += 1;
      } else {
        map.set(id, { id, label: run.characterJob, count: 1 });
      }
    }
    return [...map.values()].sort((a, b) => a.label.localeCompare(b.label));
  }, [mergedRuns]);

  /** BOSS 下拉选项：融合车合并成一项。 */
  const bossOptions = useMemo(() => {
    const map = new Map<number, { id: number; label: string; count: number }>();
    for (const run of mergedRuns) {
      const hit = map.get(run.bossId);
      if (hit) {
        hit.count += 1;
      } else {
        const label = run.comboMemberNames?.length ? '融合车（7 连）' : run.bossName;
        map.set(run.bossId, { id: run.bossId, label, count: 1 });
      }
    }
    return [...map.values()].sort((a, b) => a.label.localeCompare(b.label));
  }, [mergedRuns]);

  /** 应用两个筛选（与关系） */
  const visibleRuns = useMemo(
    () =>
      mergedRuns.filter((run) => {
        if (charFilter && String(run.characterId ?? '') !== charFilter) {
          return false;
        }
        if (bossFilter && String(run.bossId) !== bossFilter) {
          return false;
        }
        return true;
      }),
    [mergedRuns, charFilter, bossFilter],
  );

  const pending = mergedRuns.filter((run) => run.status !== 'DONE');
  const finished = mergedRuns.filter((run) => run.status === 'DONE');

  return (
    <div>
      <div className="page-head">
        <h1>我的车次</h1>
        {user?.playerId ? (
          <span className="sub">
            绑定群友：<strong>{user.playerNickname}</strong> · 待打 {pending.length} 趟 · 已完成{' '}
            {finished.length} 趟
          </span>
        ) : (
          <span className="sub">登录并绑定自己的群友后，就能标记自己车的攻坚状态</span>
        )}
      </div>

      {/* 快速筛选：按角色 / 按 BOSS（可叠加，只列真的排到的） */}
      {user?.playerId && mergedRuns.length > 0 ? (
        <div className="toolbar" style={{ marginTop: -6 }}>
          <select
            value={charFilter}
            onChange={(e) => setCharFilter(e.target.value)}
            style={{ width: 180 }}
            title="按角色筛选：只看这个角色要打的车"
          >
            <option value="">全部角色（{charOptions.length}）</option>
            {charOptions.map((c) => (
              <option key={c.id} value={String(c.id)}>
                {c.label}（{c.count} 趟）
              </option>
            ))}
          </select>
          <select
            value={bossFilter}
            onChange={(e) => setBossFilter(e.target.value)}
            style={{ width: 195 }}
            title="按 BOSS 筛选"
          >
            <option value="">全部 BOSS（{bossOptions.length}）</option>
            {bossOptions.map((b) => (
              <option key={b.id} value={String(b.id)}>
                {b.label}（{b.count} 趟）
              </option>
            ))}
          </select>
          {charFilter || bossFilter ? (
            <>
              <button
                className="btn small"
                onClick={() => {
                  setCharFilter('');
                  setBossFilter('');
                }}
              >
                清除筛选
              </button>
              <span className="muted">
                筛出 {visibleRuns.length} / {mergedRuns.length} 趟
              </span>
            </>
          ) : null}
        </div>
      ) : null}

      {!user ? (
        <div className="card">
          <div className="card-title">这里是「我的车次」</div>
          <p className="muted" style={{ marginTop: 0 }}>
            登录后这里只显示<strong>你自己要打的 BOSS 与车次</strong>，可以标记「未完成 / 已完成」。
          </p>
          <p className="muted">
            还没账号？找管理员要你的 QQ 号账号。想先看全群排班，去{' '}
            <Link to="/runs">车次一览表</Link>。
          </p>
          <Link className="btn primary" to="/login">
            去登录
          </Link>
        </div>
      ) : (
        <>
          {/*
            这里以前是一个「选择你的群友昵称 + 解除绑定」的下拉框，但那是多余的：
            账号是管理员按 QQ 创建的，创建时就已经和同名群友绑定好了，
            登录后服务端也一直用这个绑定来识别「你是谁」——让用户自己再选一次
            既没有意义，选错还会把状态标记到别人的车上。
            所以现在只做**只读展示**，绑定关系由管理员在系统设置里维护。
          */}
          <div className="card">
            <div className="card-title">我的群友身份</div>
            {myPlayer ? (
              <p className="muted" style={{ marginTop: 0 }}>
                当前账号已关联群友 <strong style={{ color: 'var(--text)' }}>{myPlayer.nickname}</strong>
                {myPlayer.characters.length
                  ? `（${myPlayer.characters.map((c) => c.job).join(' / ')}）`
                  : ''}
                。你的车次与完成状态都记在这个群友名下。
                <br />
                绑定关系由管理员在「系统设置」里维护，如有错误请联系管理员。
              </p>
            ) : (
              <p className="muted" style={{ marginTop: 0 }}>
                这个账号还没有关联群友，所以看不到「我的车次」。
                请联系管理员在「系统设置」里给你绑定群友。
              </p>
            )}
          </div>

          {!user.playerId ? (
            <p className="muted">先在上面选择你的群友昵称，然后这里就会列出你要打的车。</p>
          ) : loading ? (
            <p className="muted">加载中…</p>
          ) : mergedRuns.length === 0 ? (
            <p className="muted">还没有排到你的车，等管理员安排吧。</p>
          ) : visibleRuns.length === 0 ? (
            <p className="muted">当前筛选条件下没有车次，换个角色/BOSS 或点「清除筛选」。</p>
          ) : (
            <div className="card-grid" style={{ marginTop: 16 }}>
              {/* 排序已在 mergeMyRuns 里做好：未完成的在前、已完成的沉底。
                  这里在「第一个已完成」之前插一条分隔线，让分界一眼可见。 */}
              {visibleRuns.map((item, index) => {
                const full = runById.get(item.runId);
                const showDoneDivider =
                  item.status === 'DONE' && index > 0 && visibleRuns[index - 1].status !== 'DONE';
                return (
                  <Fragment key={`${item.runId}-${item.groupRunIds.length}-${index}`}>
                    {showDoneDivider ? (
                      <div className="done-divider">
                        <span>以下 {finished.length} 趟已完成</span>
                      </div>
                    ) : null}
                    <article
                      className={`run-tile ${item.status === 'DONE' ? 'is-done' : ''} is-mine`}
                    >
                    <header className="tile-head">
                      <span className="tile-boss">
                        {item.comboMemberNames ? '融合车' : item.bossName}
                      </span>
                      {item.comboMemberNames ? (
                        <span className="tag tier-H">{item.comboMemberNames.length} 连</span>
                      ) : null}
                      <span className={tierClass(item.tier)}>{tierText(item.tier, item.tierLabel ?? undefined)}</span>
                      <span className="tile-index">第 {item.runIndex} 趟</span>
                      <div className="spacer" />
                      <span className={`tag status-${item.status}`}>{statusLabel(item.status)}</span>
                    </header>
                    <div className="tile-time">
                      🎮 我用 <strong>{item.characterJob}</strong>（{item.characterPower}M）上这趟车
                    </div>
                    {item.scheduled ? <div className="tile-time">🕘 {item.scheduled}</div> : null}
                    {item.comboMemberNames ? (
                      <div className="tile-time" title={item.comboMemberNames.join(' → ')}>
                        🔗 要打全打：{item.comboMemberNames.join(' · ')}
                      </div>
                    ) : null}
                    {full ? (
                      <ul className="tile-members">
                        {full.members.map((member) => (
                          <li key={member.id} title={memberTooltip(member)}>
                            <span className="nm">{member.playerNickname}</span>
                            <span className="jb">{member.job}</span>
                            {member.mainChar ? <span className="tag main-tag">主号</span> : null}
                            <span className="pw">{member.power}M</span>
                          </li>
                        ))}
                      </ul>
                    ) : null}
                    <footer className="tile-foot">
                      <span className="muted">
                        {item.memberCount}/{item.capacity} 人
                      </span>
                      <div className="spacer" />
                      {item.status === 'DONE' ? (
                        <button className="btn small" onClick={() => void mark(item.groupRunIds, 'PLANNED')}>
                          改回未完成
                        </button>
                      ) : (
                        <button className="btn small primary" onClick={() => void mark(item.groupRunIds, 'DONE')}>
                          {item.groupRunIds.length > 1 ? '✓ 整趟打完了' : '✓ 我打完了'}
                        </button>
                      )}
                    </footer>
                    </article>
                  </Fragment>
                );
              })}
            </div>
          )}
        </>
      )}
    </div>
  );
}
