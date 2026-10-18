import type {
  BossView,
  CharacterView,
  GroupView,
  MemberView,
  LoginResponse,
  Overview,
  PlayerRunView,
  PlayerView,
  RunView,
  WeekDetail,
  WeekView,
  UserView,
  VoiceRooms,
  VoiceToken,
  ClientInfo,
} from './types';

const TOKEN_KEY = 'gmsr.token';
const REFRESH_KEY = 'gmsr.refresh';

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string | null) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_KEY);
}

export function setRefreshToken(token: string | null) {
  if (token) {
    localStorage.setItem(REFRESH_KEY, token);
  } else {
    localStorage.removeItem(REFRESH_KEY);
  }
}

/** 同时保存双 token（登录 / 刷新成功后调用）。 */
export function setTokens(accessToken: string | null, refreshToken: string | null) {
  setToken(accessToken);
  setRefreshToken(refreshToken);
}

/** 清空双 token（登出 / 刷新失败）。 */
export function clearTokens() {
  setTokens(null, null);
}

/**
 * access token 过期时的「无感刷新」。
 *
 * 多个请求同时收到 401 时，只发起一次刷新，其余等同一个 Promise，
 * 避免并发刷新导致 refresh token 被轮换多次、后面的请求全部失败。
 */
let refreshing: Promise<boolean> | null = null;

async function refreshAccessToken(): Promise<boolean> {
  if (refreshing) {
    return refreshing;
  }
  refreshing = (async () => {
    const refreshToken = getRefreshToken();
    if (!refreshToken) {
      return false;
    }
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!res.ok) {
        // 7 天没使用 → refresh token 也过期了，必须重新登录
        clearTokens();
        return false;
      }
      const data = (await res.json()) as { token?: string; refreshToken?: string };
      if (!data.token) {
        clearTokens();
        return false;
      }
      setTokens(data.token, data.refreshToken ?? null);
      return true;
    } catch {
      return false;
    } finally {
      // 留一点缓冲，避免同一批请求的 401 各自触发刷新
      setTimeout(() => {
        refreshing = null;
      }, 0);
    }
  })();
  return refreshing;
}

/**
 * 登录态失效时的回调。
 *
 * refresh token 也过期（7 天没使用）或被服务端拒绝时，界面必须回到未登录状态，
 * 否则会一直显示「已登录」但点什么都没反应。
 */
let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

export class ApiError extends Error {
  code: string;

  constructor(message: string, code = 'ERROR') {
    super(message);
    this.code = code;
  }
}

async function request<T>(path: string, options: RequestInit = {}, retried = false): Promise<T> {
  const headers = new Headers(options.headers ?? {});
  if (options.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getToken();
  if (token) {
    // SA-Token 约定的请求头（原来是 Authorization: Bearer）
    headers.set('gmsr-token', token);
  }
  const response = await fetch(`/api${path}`, { ...options, headers });

  if (response.status === 401 && !retried && !path.startsWith('/auth/refresh')) {
    // access token 过期（2 小时）→ 用 refresh token 静默换一个新的，然后自动重试原请求。
    // 用户完全无感知；只有 refresh token 也过期（连续 7 天没用过系统）才会真正掉线。
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      return request<T>(path, options, true);
    }
    clearTokens();
    onUnauthorized?.();
  } else if (response.status === 401) {
    clearTokens();
    onUnauthorized?.();
  }

  if (response.status === 204) {
    return undefined as T;
  }
  const text = await response.text();
  const payload = text ? safeParse(text) : null;
  if (!response.ok) {
    const message = (payload && (payload.message as string)) || `请求失败（${response.status}）`;
    throw new ApiError(message, (payload && (payload.code as string)) || 'ERROR');
  }
  return payload as T;
}

function safeParse(text: string): Record<string, unknown> | null {
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch {
    return null;
  }
}

export const api = {
  login: (username: string, password: string) =>
    request<LoginResponse & { mustChangePassword?: boolean }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  /** 登出：让服务端吊销会话与 refresh token */
  logout: () =>
    request<{ ok: boolean }>('/auth/logout', {
      method: 'POST',
      body: JSON.stringify({ refreshToken: getRefreshToken() }),
    }),
  /** 客户端环境（服务端按 User-Agent 判断，用于选手机版/电脑版） */
  clientInfo: () => request<ClientInfo>('/client-info'),
  /** 用户改自己的显示名称 */
  updateDisplayName: (displayName: string) =>
    request<UserView>('/auth/display-name', { method: 'PUT', body: JSON.stringify({ displayName }) }),
  /** BOSS 语音频道列表 */
  voiceRooms: () => request<VoiceRooms>('/voice/rooms'),
  /** 换取进房 token */
  voiceToken: (roomId: string) =>
    request<VoiceToken>('/voice/rooms/' + roomId + '/token', { method: 'POST' }),
  me: () => request<UserView>('/auth/me'),
  changePassword: (oldPassword: string, newPassword: string) =>
    request<void>('/auth/password', {
      method: 'POST',
      body: JSON.stringify({ oldPassword, newPassword }),
    }),

  overview: () => request<Overview>('/stats/overview'),

  players: () => request<PlayerView[]>('/players'),
  createPlayer: (body: { nickname: string; qq?: string | null; remark?: string; sortOrder?: number }) =>
    request<PlayerView>('/players', { method: 'POST', body: JSON.stringify(body) }),
  updatePlayer: (id: number, body: {
    nickname: string;
    qq?: string | null;
    remark?: string;
    active?: boolean;
    sortOrder?: number;
  }) =>
    request<PlayerView>(`/players/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deletePlayer: (id: number) => request<void>(`/players/${id}`, { method: 'DELETE' }),

  createCharacter: (body: { playerId: number; job: string; power: number; mainChar?: boolean; note?: string }) =>
    request<CharacterView>('/characters', { method: 'POST', body: JSON.stringify(body) }),
  updateCharacter: (
    id: number,
    body: { playerId: number; job: string; power: number; mainChar?: boolean; note?: string; sortOrder?: number },
  ) => request<CharacterView>(`/characters/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteCharacter: (id: number) => request<void>(`/characters/${id}`, { method: 'DELETE' }),

  // 我的角色（本人维护，服务端校验归属）
  myCharacters: () => request<CharacterView[]>('/me/characters'),
  createMyCharacter: (body: { job: string; power: number; mainChar?: boolean; note?: string }) =>
    request<CharacterView>('/me/characters', { method: 'POST', body: JSON.stringify(body) }),
  updateMyCharacter: (
    id: number,
    body: { job: string; power: number; mainChar?: boolean; note?: string },
  ) => request<CharacterView>(`/me/characters/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteMyCharacter: (id: number) => request<void>(`/me/characters/${id}`, { method: 'DELETE' }),

  groups: () => request<GroupView[]>('/boss-groups'),
  bosses: () => request<BossView[]>('/bosses'),
  createGroup: (body: { name: string; sortOrder?: number; note?: string }) =>
    request<GroupView>('/boss-groups', { method: 'POST', body: JSON.stringify(body) }),
  updateGroup: (id: number, body: { name: string; sortOrder?: number; note?: string }) =>
    request<GroupView>(`/boss-groups/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteGroup: (id: number) => request<void>(`/boss-groups/${id}`, { method: 'DELETE' }),
  createBoss: (body: {
    groupId: number;
    name: string;
    iconKey?: string;
    tier?: string;
    capacity?: number;
    sortOrder?: number;
    enabled?: boolean;
    note?: string;
  }) => request<BossView>('/bosses', { method: 'POST', body: JSON.stringify(body) }),
  updateBoss: (
    id: number,
    body: {
      groupId?: number;
      name: string;
      iconKey?: string;
      tier?: string;
      capacity?: number;
      sortOrder?: number;
      enabled?: boolean;
      note?: string;
    },
  ) => request<BossView>(`/bosses/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteBoss: (id: number) => request<void>(`/bosses/${id}`, { method: 'DELETE' }),

  runs: () => request<RunView[]>('/runs'),
  runsOfWeek: (weekKey: string) => request<RunView[]>(`/weeks/${weekKey}/runs`),
  /** 上周 / 本周 / 下周 各 BOSS 的趟数：weekKey -> (bossId -> 趟数) */
  runCounts: () => request<Record<string, Record<string, number>>>('/weeks/run-counts'),
  /** 按月刷新的 BOSS 用的周期（上月/本月/下月） */
  monthTriples: () => request<WeekDetail>('/weeks/month-triples'),
  weekTriples: () => request<WeekDetail>('/weeks/triples'),
  weeks: () => request<{ currentWeekKey: string; weeks: WeekView[] }>('/weeks'),
  /** 按单个 BOSS 把某周的排班复制到另一周 */
  copyBossWeek: (fromWeekKey: string, toWeekKey: string, bossId: number, tier?: string, overwrite = false) =>
    request<WeekView>('/weeks/copy-boss', {
      method: 'POST',
      body: JSON.stringify({ fromWeekKey, toWeekKey, bossId, tier, overwrite }),
    }),
  copyWeek: (fromWeekKey: string, toWeekKey: string, overwrite = false) =>
    request<WeekView>('/weeks/copy', {
      method: 'POST',
      body: JSON.stringify({ fromWeekKey, toWeekKey, overwrite }),
    }),
  createRun: (body: {
    weekKey?: string | null;
    bossId: number;
    tier?: string;
    runIndex?: number;
    scheduled?: string;
    status?: string;
    note?: string;
  }) =>
    request<RunView>('/runs', { method: 'POST', body: JSON.stringify(body) }),
  updateRun: (
    id: number,
    body: {
      weekKey?: string | null;
      bossId?: number;
      tier?: string;
      runIndex?: number;
      scheduled?: string;
      status?: string;
      note?: string;
    },
  ) => request<RunView>(`/runs/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteRun: (id: number) => request<void>(`/runs/${id}`, { method: 'DELETE' }),
  copyRun: (id: number) => request<RunView>(`/runs/${id}/copy`, { method: 'POST' }),
  setRunStatus: (id: number, status: 'DONE' | 'PLANNED' | 'CANCELED') =>
    request<RunView>(`/runs/${id}/status`, { method: 'PUT', body: JSON.stringify({ status }) }),
  myMembers: (id: number) => request<MemberView[]>(`/runs/${id}/my-members`),
  addMember: (runId: number, body: { characterId: number; slotNo?: number; role?: string; status?: string; note?: string }) =>
    request<RunView>(`/runs/${runId}/members`, { method: 'POST', body: JSON.stringify(body) }),
  updateMember: (
    runId: number,
    memberId: number,
    body: { slotNo?: number; role?: string; status?: string; note?: string },
  ) => request<RunView>(`/runs/${runId}/members/${memberId}`, { method: 'PUT', body: JSON.stringify(body) }),
  removeMember: (runId: number, memberId: number) =>
    request<RunView>(`/runs/${runId}/members/${memberId}`, { method: 'DELETE' }),
  /** 拖拽换位：把一个席位拖到另一个席位上，两人对调（同一 BOSS 的车次之间） */
  swapMembers: (runId: number, memberId: number, otherMemberId: number) =>
    request<{ source: RunView; target: RunView }>(`/runs/${runId}/members/${memberId}/swap`, {
      method: 'POST',
      body: JSON.stringify({ otherMemberId }),
    }),
  /** 拖拽调车：把席位从一趟车挪到另一趟车 */
  moveMember: (runId: number, memberId: number, targetRunId: number, slotNo?: number) =>
    request<{ source: RunView; target: RunView }>(`/runs/${runId}/members/${memberId}/move`, {
      method: 'POST',
      body: JSON.stringify({ targetRunId, slotNo }),
    }),
  replaceMembers: (runId: number, members: Array<{ characterId: number; role?: string; status?: string; note?: string }>) =>
    request<RunView>(`/runs/${runId}/members`, { method: 'PUT', body: JSON.stringify({ members }) }),
  playerRuns: (playerId: number, weekKey?: string) =>
    request<PlayerRunView[]>(
      `/players/${playerId}/runs${weekKey ? `?week=${weekKey}` : ''}`,
    ),

  users: () => request<UserView[]>('/admin/users'),
  createUser: (body: {
    username: string;
    password: string;
    displayName: string;
    role?: string;
    enabled?: boolean;
    canSchedule?: boolean;
    playerId?: number | null;
  }) =>
    request<UserView>('/admin/users', { method: 'POST', body: JSON.stringify(body) }),
  updateUser: (
    id: number,
    body: {
      username: string;
      password?: string;
      displayName: string;
      role?: string;
      enabled?: boolean;
      canSchedule?: boolean;
      playerId?: number | null;
    },
  ) => request<UserView>(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteUser: (id: number) => request<void>(`/admin/users/${id}`, { method: 'DELETE' }),
  resetUserPassword: (id: number) =>
    request<{ username: string; defaultPassword: string }>(`/admin/users/${id}/reset-password`, { method: 'POST' }),
};
