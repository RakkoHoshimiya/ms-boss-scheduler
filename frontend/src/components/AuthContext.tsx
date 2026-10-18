import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import { api, clearTokens, getToken, setTokens, setUnauthorizedHandler } from '../api/client';
import type { UserView } from '../api/types';

interface AuthState {
  user: UserView | null;
  ready: boolean;
  login: (username: string, password: string) => Promise<{ mustChangePassword: boolean }>;
  logout: () => void;
  refresh: () => Promise<void>;
  canEdit: boolean;
  isAdmin: boolean;
  /** 能不能进 boss排班 改排班 */
  canSchedule: boolean;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<UserView | null>(null);
  const [ready, setReady] = useState(false);

  /**
   * 注册「登录态失效」回调。
   * 任何请求收到 401（token 过期或被服务端拒绝）时，api 层会清掉本地 token 并调用这里，
   * 界面随即回到未登录状态 —— 否则会一直显示「已登录」但所有操作都失败。
   */
  useEffect(() => {
    setUnauthorizedHandler(() => {
      setUser(null);
    });
    return () => setUnauthorizedHandler(null);
  }, []);

  useEffect(() => {
    if (!getToken()) {
      setReady(true);
      return;
    }
    // SA-Token 的 access token 是不透明随机串（不是 JWT），本地解析不出用户信息，
    // 所以启动时直接问服务端「我是谁」。若 access token 已过期，api 层会自动用
    // refresh token 静默刷新后重试 —— 用户 7 天内用过系统就不会被要求重新登录。
    api
      .me()
      .then(setUser)
      .catch(() => {
        clearTokens();
        setUser(null);
      })
      .finally(() => setReady(true));
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    const result = await api.login(username, password);
    // 双 token：access 短命（2 小时）用于请求，refresh 7 天滑动用于无感续期
    setTokens(result.token, result.refreshToken ?? null);
    setUser(result.user);
    return { mustChangePassword: Boolean(result.mustChangePassword ?? result.user.mustChangePassword) };
  }, []);

  const refresh = useCallback(async () => {
    const me = await api.me();
    setUser(me);
  }, []);

  const logout = useCallback(() => {
    // 先让服务端吊销会话与 refresh token（失败也要清本地，不能让用户卡在登录态）
    void api.logout().catch(() => undefined);
    clearTokens();
    setUser(null);
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      user,
      ready,
      login,
      logout,
      refresh,
      canEdit: user?.role === 'ADMIN' || user?.role === 'EDITOR',
      isAdmin: user?.role === 'ADMIN',
      canSchedule: user?.role === 'ADMIN' || Boolean(user?.canSchedule),
    }),
    [user, ready, login, logout, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error('useAuth 必须在 AuthProvider 内使用');
  }
  return ctx;
}
