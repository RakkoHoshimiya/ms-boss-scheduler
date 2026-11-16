import { createNoteBlobCache, type DownloadProgress } from '../features/note-blob-cache';
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
  UserView,
  VoiceRooms,
  VoiceToken,
  ClientInfo,
} from './types';

const TOKEN_KEY = 'gmsr.token';
const REFRESH_KEY = 'gmsr.refresh';
const noteBlobs = createNoteBlobCache();
let observedToken = localStorage.getItem(TOKEN_KEY);
let authSessionVersion = 0;

export function getToken(): string | null {
  const token = localStorage.getItem(TOKEN_KEY);
  // Another tab can log out or switch accounts without calling this module's setters.
  if (token !== observedToken) {
    observedToken = token; authSessionVersion++; noteBlobs.clear();
  }
  return token;
}

export function setToken(token: string | null, preserveImages = false) {
  if (!preserveImages) { authSessionVersion++; noteBlobs.clear(); }
  observedToken = token;
  if (token) {
    localStorage.setItem(TOKEN_KEY, token);
  } else {
    localStorage.removeItem(TOKEN_KEY);
  }
}

export function getAuthSessionVersion(): number {
  getToken();
  return authSessionVersion;
}

function requireCurrentSession(version: number) {
  if (getAuthSessionVersion() !== version) throw new ApiError('登录状态已变更，请重新操作', 'SESSION_CHANGED');
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
let refreshing: { version: number; promise: Promise<boolean> } | null = null;

async function refreshAccessToken(version: number): Promise<boolean> {
  requireCurrentSession(version);
  if (refreshing?.version === version) return refreshing.promise;
  const refreshToken = getRefreshToken();
  if (!refreshToken) return false;
  const task = (async () => {
    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      requireCurrentSession(version);
      if (!res.ok) {
        // 7 天没使用 → refresh token 也过期了，必须重新登录
        return false;
      }
      const data = (await res.json()) as { token?: string; refreshToken?: string };
      requireCurrentSession(version);
      if (!data.token) {
        return false;
      }
      setToken(data.token, true); // Same account/session: keep its current image transfers alive.
      setRefreshToken(data.refreshToken ?? null);
      return true;
    } catch {
      requireCurrentSession(version);
      return false;
    } finally {
      // 留一点缓冲，避免同一批请求的 401 各自触发刷新
      setTimeout(() => {
        if (refreshing?.promise === task) refreshing = null;
      }, 0);
    }
  })();
  refreshing = { version, promise: task };
  return task;
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

async function request<T>(path: string, options: RequestInit = {}, retried = false, blob = false, progress?: (value: DownloadProgress) => void): Promise<T> {
  const headers = new Headers(options.headers ?? {});
  if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  const token = getToken();
  // Keep the token and the generation from the same storage observation. A second
  // getter here could observe another tab's B token but retain the earlier A header.
  const version = authSessionVersion;
  requireCurrentSession(version);
  if (token) {
    // SA-Token 约定的请求头（原来是 Authorization: Bearer）
    headers.set('gmsr-token', token);
  }
  const response = await fetch(`/api${path}`, { ...options, headers });
  requireCurrentSession(version);

  const authAction = /^\/auth\/(login|logout|refresh)$/.test(path);
  if (response.status === 401 && !authAction) {
    // access token 过期（2 小时）→ 用 refresh token 静默换一个新的，然后自动重试原请求。
    // 用户完全无感知；只有 refresh token 也过期（连续 7 天没用过系统）才会真正掉线。
    if (!retried) {
      // A concurrent request may already have refreshed this same session's access token.
      const refreshed = Boolean(getToken() && getToken() !== token) || await refreshAccessToken(version);
      requireCurrentSession(version);
      if (refreshed) return request<T>(path, options, true, blob, progress);
    }
    clearTokens();
    onUnauthorized?.();
    throw new ApiError('请先登录', 'UNAUTHORIZED');
  }

  if (response.status === 204) {
    return undefined as T;
  }
  if (blob && response.ok) {
    if (!progress || !response.body) {
      const result = await response.blob(); requireCurrentSession(version); return result as T;
    }
    const total = Number(response.headers.get('Content-Length')) || 0;
    let loaded = 0;
    const chunks: Uint8Array[] = [];
    const reader = response.body.getReader();
    try {
      progress({ loaded, total });
      while (true) {
        const chunk = await reader.read();
        if (chunk.done) break;
        chunks.push(chunk.value); loaded += chunk.value.byteLength; progress({ loaded, total });
      }
      requireCurrentSession(version);
      progress({ loaded, total: loaded });
      return new Blob(chunks, { type: response.headers.get('Content-Type') ?? '' }) as T;
    } finally { reader.releaseLock(); }
  }
  const text = await response.text();
  requireCurrentSession(version);
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
  /** 完成状态的重置时间（上次 / 下次） */
  statusReset: () => request<{ lastResetAt?: string | null; nextResetAt?: string }>('/status-reset'),
  createRun: (body: {
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
  playerRuns: (playerId: number) => request<PlayerRunView[]>(`/players/${playerId}/runs`),

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
      displayName: string;
      role?: string;
      enabled?: boolean;
      canSchedule?: boolean;
      password?: string;
      /*
       * 注意：编辑账号**不再**支持改 playerId。
       * 以前前端不传该字段时，后端把它当成「解绑群友」，
       * 导致管理员改个名字就把账号与群友的绑定清掉了。
       * 后端现在用独立的 UpdateRequest 类型，结构上就没有这个字段。
       */
    },
  ) => request<UserView>(`/admin/users/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  deleteUser: (id: number) => request<void>(`/admin/users/${id}`, { method: 'DELETE' }),
  resetUserPassword: (id: number) =>
    request<{ username: string; defaultPassword: string }>(`/admin/users/${id}/reset-password`, { method: 'POST' }),
};

export interface GuideNote { id: number; version: number; title: string; content: string; pinned: boolean; updatedAt: string; deletedAt?: string | null }
export interface NoteAttachment { id: string; name: string; size: number; mediaType: string }
export interface NoteDetail { note: GuideNote; attachments: NoteAttachment[] }
export interface NoteDraft { id: string; version?: number; noteId: number | null; sourceVersion: number | null; title: string; content: string; pinned: boolean; updatedAt: string; deletedAt?: string | null; attachments: NoteAttachment[] }
export type DraftWrite = Omit<NoteDraft, 'attachments' | 'updatedAt' | 'deletedAt'> & { attachmentIds: string[] };
function noteImage(aid: string, preview: boolean, progress?: (value: DownloadProgress) => void) {
 getToken(); // Detect a cross-tab account change before returning an in-memory cache hit.
 const version = authSessionVersion;
 requireCurrentSession(version);
 const key = `${aid}/${preview ? 'preview' : 'original'}`;
 const load = noteBlobs.get(key, async (sessionSignal, notify) => {
  const controller = new AbortController();
  const abort = () => controller.abort();
  sessionSignal.addEventListener('abort', abort, { once: true });
  if (sessionSignal.aborted) controller.abort();
  const timeout = setTimeout(abort, 60000);
  try { return await request<Blob>(`/notes/attachments/${aid}${preview ? '/preview' : ''}`, { signal: controller.signal }, false, true, notify); }
  catch (error) {
   if (controller.signal.aborted) throw new Error('图片加载超时或已取消，请重试');
   throw error;
  } finally { clearTimeout(timeout); sessionSignal.removeEventListener('abort', abort); }
 }, progress);
 // All callers settle; detach progress observers even when a component has disappeared.
 return { promise: load.promise.then(blob => { requireCurrentSession(version); return blob; }).finally(load.unsubscribe), unsubscribe: load.unsubscribe };
}
export const notesApi = {
 list: () => request<GuideNote[]>('/notes'),
 detail: (id: number) => request<NoteDetail>(`/notes/${id}`),
 save: (note: Partial<GuideNote> & { attachmentIds?: string[]; draftId?: string; draftVersion?: number }) => request<GuideNote>(note.id ? `/notes/${note.id}` : '/notes', {method: note.id ? 'PUT' : 'POST', body: JSON.stringify(note)}),
 trash: () => request<GuideNote[]>('/notes/trash'),
 restore: (id: number) => request<GuideNote>(`/notes/${id}/restore`, {method:'POST'}),
 drafts: () => request<NoteDraft[]>('/notes/drafts'),
 draftTrash: () => request<NoteDraft[]>('/notes/drafts/trash'),
 saveDraft: (draft: DraftWrite) => request<NoteDraft>(`/notes/drafts/${draft.id}`, {method:'PUT',body:JSON.stringify(draft)}),
 forkDraft: (draft: DraftWrite) => request<NoteDraft>('/notes/drafts/fork', {method:'POST',body:JSON.stringify(draft)}),
 deleteDraft: (id: string) => request<void>(`/notes/drafts/${id}`, {method:'DELETE'}),
 restoreDraft: (id: string) => request<NoteDraft>(`/notes/drafts/${id}/restore`, {method:'POST'}),
 pin: (note: GuideNote) => request<GuideNote>(`/notes/${note.id}/pin`, {method:'PUT', body:JSON.stringify({pinned:!note.pinned, version:note.version})}),
 remove: (id: number) => request<void>(`/notes/${id}`, {method:'DELETE'}),
 upload: (id: number, file: File) => { const body=new FormData(); body.append('file',file); return request<NoteAttachment>(`/notes/${id}/attachments`, {method:'POST',body}); },
 stage: (file: File) => { const body=new FormData(); body.append('file',file); return request<NoteAttachment>('/notes/attachments', {method:'POST',body}); },
 image: noteImage,
 file: (aid: string) => noteImage(aid, false).promise,
 preview: (aid: string) => noteImage(aid, true).promise,
 discard: (aid: string) => request<void>(`/notes/attachments/${aid}`, {method:'DELETE'}),
 removeFile: (id: number, aid: string) => request<void>(`/notes/${id}/attachments/${aid}`, {method:'DELETE'}),
};
