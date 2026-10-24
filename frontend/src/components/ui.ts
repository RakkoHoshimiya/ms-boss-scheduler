export function tierClass(tier: string): string {
  return `tag tier-${tier || 'NONE'}`;
}

export function tierText(tier: string, label?: string): string {
  // 难度统一显示英文全称（Normal / Hard / Chaos / Extreme）
  if (label) {
    return label;
  }
  const found = TIER_OPTIONS.find((item) => item.value === tier);
  return found && found.value !== 'NONE' ? found.label : '—';
}

export const TIER_OPTIONS = [
  { value: 'NONE', label: '—' },
  { value: 'E', label: 'Easy' },
  { value: 'N', label: 'Normal' },
  { value: 'H', label: 'Hard' },
  { value: 'C', label: 'Chaos' },
  { value: 'EX', label: 'Extreme' },
];

/** 难度固定顺序：Easy → Normal → Hard → Chaos → Extreme */
export const TIER_ORDER = ['E', 'N', 'H', 'C', 'EX'];

/** 按固定顺序排列难度，并过滤掉 NONE / 非法值 */
export function sortTiers(tiers: (string | null | undefined)[]): string[] {
  const keep = new Set(tiers.filter((t): t is string => !!t && t !== 'NONE'));
  return TIER_ORDER.filter((t) => keep.has(t));
}

export const STATUS_OPTIONS = [
  { value: 'PLANNED', label: '待打' },
  { value: 'DONE', label: '已完成' },
  { value: 'CANCELED', label: '已取消' },
];

export const MEMBER_STATUS_OPTIONS = [
  { value: 'CONFIRMED', label: '确认' },
  { value: 'TENTATIVE', label: '待定' },
  { value: 'ABSENT', label: '请假' },
];

export const JOB_PRESETS = [
  'Kanna',
  '剑豪',
  '琳恩',
  '兔子',
  '光枪',
  '火毒',
  'Sia',
  '黑骑',
  '豹弩',
  '冰雷',
  '超能',
];

export function statusLabel(status: string): string {
  return STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

export function memberStatusLabel(status: string): string {
  return MEMBER_STATUS_OPTIONS.find((s) => s.value === status)?.label ?? status;
}

/**
 * 战力格式化。单位 M = Million（百万），例如 185M = 1.85 亿。
 * 战力本身就是以 M 为单位记录的，所以直接拼上 M。
 */
export function formatPower(power: number | null | undefined): string {
  if (power === null || power === undefined || power === 0) {
    return '—';
  }
  return `${power}M`;
}

/** 战力合计（列表页用）。 */
export function formatPowerTotal(power: number): string {
  return `${power}M`;
}

/** 角色悬停提示：把角色备注、主号等信息汇总成一段文字。 */
export function memberTooltip(member: {
  playerNickname: string;
  job: string;
  power: number;
  characterNote?: string | null;
  mainChar?: boolean;
  status?: string;
}): string {
  const lines = [`${member.playerNickname} · ${member.job} · ${member.power}M`];
  if (member.mainChar) {
    lines.push('主号');
  }
  if (member.characterNote && member.characterNote.trim()) {
    lines.push(`备注：${member.characterNote.trim()}`);
  }
  return lines.join('\n');
}
