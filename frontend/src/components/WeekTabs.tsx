import type { WeekDetail } from '../api/types';

export type WeekSlot = 'last' | 'this' | 'next';

export const WEEK_SLOT_LABEL: Record<WeekSlot, string> = {
  last: '上周',
  this: '本周',
  next: '下周',
};

/**
 * 按月刷新的 BOSS（老黑能量车，一个月才刷一次）用的标签。
 * 它的周期不是周四 CD，所以显示「上月 / 本月 / 下月」才不会让人误解。
 */
export const MONTH_SLOT_LABEL: Record<WeekSlot, string> = {
  last: '上月',
  this: '本月',
  next: '下月',
};

export function weekOf(detail: WeekDetail, slot: WeekSlot) {
  if (slot === 'last') {
    return detail.lastWeek;
  }
  if (slot === 'next') {
    return detail.nextWeek;
  }
  return detail.thisWeek;
}

/** 上周 / 本周 / 下周 三个子菜单。
 *  counts 传入的是「当前 BOSS」在各周期的真实趟数；不传时才回退到全周总数。 */
export default function WeekTabs({
  detail,
  active,
  onChange,
  counts,
  monthly = false,
}: {
  detail: WeekDetail;
  active: WeekSlot;
  onChange: (slot: WeekSlot) => void;
  counts?: Partial<Record<WeekSlot, number>>;
  /** 该 BOSS 是否按月刷新：为 true 时标签显示 上月/本月/下月 */
  monthly?: boolean;
}) {
  const slots: WeekSlot[] = ['last', 'this', 'next'];
  const labels = monthly ? MONTH_SLOT_LABEL : WEEK_SLOT_LABEL;
  return (
    <div className="week-tabs">
      {slots.map((slot) => {
        const week = weekOf(detail, slot);
        const isActive = slot === active;
        return (
          <button
            key={slot}
            type="button"
            className={`week-tab ${isActive ? 'active' : ''}`}
            onClick={() => onChange(slot)}
          >
            <span className="name">
              {labels[slot]}
              {slot === 'this' ? <em>当前</em> : null}
            </span>
            <span className="range">{week.label}</span>
            <span className="count">{counts?.[slot] ?? week.runCount} 趟</span>
          </button>
        );
      })}
    </div>
  );
}
