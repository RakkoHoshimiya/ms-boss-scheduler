import { useState } from 'react';
import { api } from '../api/client';
import type { RunView } from '../api/types';
import Modal from './Modal';
import { useToast } from './Toast';
import { STATUS_OPTIONS, TIER_OPTIONS } from './ui';

/** 车次设置弹窗：难度、序号、时间安排、完成情况、备注。 */
export default function RunFormModal({
  bossId,
  bossName,
  defaultTier,
  initial,
  onClose,
  onSaved,
}: {
  bossId: number;
  bossName?: string;
  /** 新建车次时归属的排班周期 */
  /** 新建车次时预选的难度 */
  defaultTier?: string | null;
  initial: RunView | null;
  onClose: () => void;
  onSaved: () => void;
}) {
  const { notify } = useToast();
  const [form, setForm] = useState({
    tier: initial?.tier ?? defaultTier ?? '',
    runIndex: initial ? String(initial.runIndex) : '',
    scheduled: initial?.scheduled ?? '',
    status: initial?.status ?? 'PLANNED',
    note: initial?.note ?? '',
  });
  const [busy, setBusy] = useState(false);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setBusy(true);
    try {
      const body = {
        bossId,
        tier: form.tier || undefined,
        runIndex: form.runIndex ? Number(form.runIndex) : undefined,
        scheduled: form.scheduled,
        status: form.status,
        note: form.note,
      };
      if (initial) {
        await api.updateRun(initial.id, body);
      } else {
        await api.createRun(body);
      }
      notify('已保存车次');
      onSaved();
    } catch (error) {
      notify(error instanceof Error ? error.message : '保存失败', 'error');
    } finally {
      setBusy(false);
    }
  };

  const name = initial?.bossName ?? bossName ?? '';

  return (
    <Modal
      title={initial ? `设置车次 · ${name} 第 ${initial.runIndex} 趟` : `新增车次${name ? ` · ${name}` : ''}`}
      onClose={onClose}
      footer={
        <>
          <button className="btn" type="button" onClick={onClose}>
            取消
          </button>
          <button className="btn primary" type="submit" form="run-form" disabled={busy}>
            保存
          </button>
        </>
      }
    >
      <form id="run-form" onSubmit={submit}>
        <label className="field">
          <span>难度（留空沿用 BOSS 默认难度）</span>
          <select value={form.tier} onChange={(e) => setForm({ ...form, tier: e.target.value })}>
            <option value="">沿用默认</option>
            {TIER_OPTIONS.filter((tier) => tier.value !== 'NONE').map((tier) => (
              <option key={tier.value} value={tier.value}>
                {tier.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>车次序号（留空自动排到最后一趟）</span>
          <input
            type="number"
            min={1}
            value={form.runIndex}
            onChange={(e) => setForm({ ...form, runIndex: e.target.value })}
          />
        </label>
        <label className="field">
          <span>时间安排（自由填写，如「周五 21:00」）</span>
          <input
            type="text"
            value={form.scheduled ?? ''}
            onChange={(e) => setForm({ ...form, scheduled: e.target.value })}
          />
        </label>
        <label className="field">
          <span>完成情况（改完记得保存）</span>
          <select
            value={form.status}
            onChange={(e) => setForm({ ...form, status: e.target.value as RunView['status'] })}
          >
            {STATUS_OPTIONS.map((status) => (
              <option key={status.value} value={status.value}>
                {status.label}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>备注</span>
          <input type="text" value={form.note ?? ''} onChange={(e) => setForm({ ...form, note: e.target.value })} />
        </label>
      </form>
    </Modal>
  );
}
