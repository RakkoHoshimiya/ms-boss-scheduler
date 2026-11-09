import { useState } from 'react';
import { api } from '../api/client';
import type { RunView } from '../api/types';
import { useToast } from './Toast';
import RunFormModal from './RunFormModal';
import { memberTooltip, statusLabel, tierClass, tierText } from './ui';
import BossIcon from './BossIcon';

interface RunCardProps {
  run: RunView;
  /** 这趟车上有当前登录的群友 */
  mine: boolean;
  /** 管理员/编辑：可以改名单、设置、删除 */
  canEdit: boolean;
  /** 可以标记攻坚状态 */
  canMark: boolean;
  onStatus: (run: RunView, status: 'DONE' | 'PLANNED') => void;
  onEdited: () => void;
}

/** 卡片式车次：群友登录后可在这里标记自己车的攻坚状态。 */
export default function RunCard({ run, mine, canEdit, canMark, onStatus, onEdited }: RunCardProps) {
  const { notify } = useToast();
  const [busy, setBusy] = useState(false);
  const [settingOpen, setSettingOpen] = useState(false);

  const done = run.status === 'DONE';
  const emptySlots = Math.max(0, run.capacity - run.memberCount);

  const mark = async (status: 'DONE' | 'PLANNED') => {
    setBusy(true);
    try {
      onStatus(run, status);
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    if (!window.confirm(`确认删除「${run.bossName}」第 ${run.runIndex} 趟车？`)) {
      return;
    }
    try {
      await api.deleteRun(run.id);
      notify('已删除车次');
      onEdited();
    } catch (error) {
      notify(error instanceof Error ? error.message : '删除失败', 'error');
    }
  };

  return (
    <article className={`run-tile ${done ? 'is-done' : ''} ${mine ? 'is-mine' : ''}`}>
      <header className="tile-head">
        <BossIcon iconKey={run.bossIconKey} name={run.bossName} size={26} />
        <span className="tile-boss">{run.bossName}</span>
        <span className={tierClass(run.tier)}>{tierText(run.tier, run.tierLabel)}</span>
        <span className="tile-index">第 {run.runIndex} 趟</span>
        <div className="spacer" />
        {mine ? <span className="tag mine-tag">我的车</span> : null}
        <span className={`tag status-${run.status}`}>{statusLabel(run.status)}</span>
      </header>

      {run.scheduled ? <div className="tile-time">🕘 {run.scheduled}</div> : null}

      <ul className="tile-members">
        {run.members.map((member) => (
          <li key={member.id} title={memberTooltip(member)}>
            <span className="nm">{member.playerNickname}</span>
            <span className="jb">{member.job}</span>
            {member.mainChar ? <span className="tag main-tag">主号</span> : null}
            <span className="pw">{member.power}M</span>
            {member.status !== 'CONFIRMED' ? (
              <span className="tag">{member.status === 'ABSENT' ? '请假' : '待定'}</span>
            ) : null}
          </li>
        ))}
        {Array.from({ length: emptySlots }).map((_, index) => (
          <li key={`empty-${index}`} className="empty">
            空位
          </li>
        ))}
      </ul>

      <footer className="tile-foot">
        <span className="muted">
          {run.memberCount}/{run.capacity} 人 · 总战力 {run.powerTotal}M
        </span>
        <div className="spacer" />
        {canMark ? (
          done ? (
            <button className="btn small" disabled={busy} onClick={() => mark('PLANNED')}>
              改回未完成
            </button>
          ) : (
            <button className="btn small primary" disabled={busy} onClick={() => mark('DONE')}>
              ✓ 标记已完成
            </button>
          )
        ) : (
          <span className="muted" title="只有这趟车上的群友或管理员可以标记">
            仅车上成员可标记
          </span>
        )}
        {canEdit ? (
          <>
            <button className="btn small ghost" onClick={() => setSettingOpen(true)}>
              设置
            </button>
            <button className="btn small ghost danger" onClick={remove}>
              删除
            </button>
          </>
        ) : null}
      </footer>

      {settingOpen ? (
        <RunFormModal
          bossId={run.bossId}
          initial={run}
          onClose={() => setSettingOpen(false)}
          onSaved={() => {
            setSettingOpen(false);
            onEdited();
          }}
        />
      ) : null}
    </article>
  );
}
