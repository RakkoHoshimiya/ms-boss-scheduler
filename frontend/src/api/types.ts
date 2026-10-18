export interface UserView {
  id: number;
  username: string;
  displayName: string;
  role: 'ADMIN' | 'EDITOR' | 'VIEWER';
  /** 是否有 boss排班 权限（管理员天然拥有） */
  canSchedule?: boolean;
  /** 绑定的群友（用于判断“我的车”） */
  playerId?: number | null;
  playerNickname?: string | null;
  playerQq?: string | null;
  mustChangePassword?: boolean;
}

export interface LoginResponse {
  token: string;
  expiresInSeconds: number;
  user: UserView;
  /** 刷新令牌（7 天滑动）：access token 过期时用它静默换新 */
  refreshToken?: string;
}

export interface CharacterView {
  id: number;
  playerId: number;
  playerNickname: string;
  job: string;
  power: number;
  mainChar: boolean;
  note?: string | null;
  sortOrder: number;
}

export interface PlayerView {
  id: number;
  /** 显示名称 */
  nickname: string;
  /** 登录用的 QQ 号 */
  qq?: string | null;
  remark?: string | null;
  active: boolean;
  sortOrder: number;
  characterCount: number;
  characters: CharacterView[];
}

export interface GroupView {
  id: number;
  name: string;
  sortOrder: number;
  note?: string | null;
  bossCount: number;
}

export interface BossView {
  id: number;
  groupId: number;
  groupName: string;
  name: string;
  /** 头像图标文件名 */
  iconKey?: string | null;
  tier: string;
  tierLabel: string;
  /** 这个 BOSS 实际存在的难度（排班页的难度选项以它为准） */
  tiers?: string[];
  capacity: number;
  sortOrder: number;
  enabled: boolean;
  note?: string | null;
  runCount: number;
  assignedCount: number;
  /** 融合车标记：同 key 的 BOSS 合成一个车次一起打；为空表示单独排班 */
  comboKey?: string | null;
  /** 融合车显示名 */
  comboName?: string | null;
  /** 融合车固定难度 */
  comboTier?: string | null;
  /** 是否按月刷新（老黑能量车）：周期用 上月/本月/下月 */
  monthly?: boolean;
}

export interface MemberView {
  id: number;
  characterId: number;
  playerId: number;
  playerNickname: string;
  job: string;
  power: number;
  slotNo: number;
  role: 'MEMBER' | 'LEADER' | 'SUB';
  status: 'CONFIRMED' | 'TENTATIVE' | 'ABSENT';
  /** 席位备注（预留） */
  note?: string | null;
  /** 角色备注：悬停角色时显示 */
  characterNote?: string | null;
  /** 是否主号 */
  mainChar?: boolean;
}

export interface RunView {
  id: number;
  weekKey?: string | null;
  weekLabel?: string | null;
  bossId: number;
  bossName: string;
  bossIconKey?: string | null;
  groupName: string;
  tier: string;
  tierLabel: string;
  capacity: number;
  runIndex: number;
  scheduled?: string | null;
  status: 'PLANNED' | 'DONE' | 'CANCELED';
  note?: string | null;
  memberCount: number;
  powerTotal: number;
  members: MemberView[];
}

export interface GroupProgress {
  groupId: number;
  groupName: string;
  bossCount: number;
  runCount: number;
  memberCount: number;
  capacityTotal: number;
}

export interface JobCount {
  job: string;
  characterCount: number;
  totalPower: number;
}

export interface TopPlayer {
  playerId: number;
  nickname: string;
  characterCount: number;
  totalPower: number;
  assignedSlots: number;
}

export interface Overview {
  playerCount: number;
  characterCount: number;
  bossCount: number;
  runCount: number;
  assignedSlotCount: number;
  seatCapacityTotal: number;
  doneRunCount: number;
  fillRate: number;
  groups: GroupProgress[];
  jobDistribution: JobCount[];
  topPlayers: TopPlayer[];
}

export interface PlayerRunView {
  runId: number;
  bossId: number;
  bossName: string;
  groupName: string;
  tier: string;
  tierLabel?: string | null;
  runIndex: number;
  scheduled?: string | null;
  status: 'PLANNED' | 'DONE' | 'CANCELED';
  memberCount: number;
  capacity: number;
  /** 角色 id：按角色筛选时用（只靠职业名会混淆同职业的两个角色） */
  characterId?: number;
  characterJob: string;
  characterPower: number;
  weekKey?: string | null;
  weekLabel?: string | null;
}

/** 排班周期（周四 ~ 下周三） */
export interface WeekView {
  weekKey: string;
  label: string;
  startDate: string;
  endDate: string;
  note?: string | null;
  runCount: number;
}

export interface WeekDetail {
  lastWeek: WeekView;
  thisWeek: WeekView;
  nextWeek: WeekView;
  currentWeekKey: string;
}

/** 语音频道 */
export interface VoiceRoom {
  id: string;
  name: string;
  description: string;
  icon: string;
  /** 当前在线人数 */
  onlineCount?: number;
  /** 当前在线成员显示名（列表页就能看到谁在里面） */
  online?: string[];
}

export interface VoiceRooms {
  /** 语音服务是否已开通 */
  ready: boolean;
  wsUrl?: string | null;
  rooms: VoiceRoom[];
}

export interface VoiceToken {
  token: string;
  wsUrl: string;
  roomId: string;
  identity: string;
  displayName: string;
  canPublish: boolean;
}

/** 服务端按 User-Agent 识别出的客户端环境 */
export interface ClientInfo {
  device: 'mobile' | 'tablet' | 'desktop';
  isMobile: boolean;
  isTablet: boolean;
  touch: boolean;
  ua: string;
}
