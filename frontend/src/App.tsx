import { Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './components/AuthContext';
import Layout from './components/Layout';
import RunsTablePage from './pages/RunsTablePage';
import BossSchedulePage from './pages/BossSchedulePage';
import PlayersPage from './pages/PlayersPage';
import LoginPage from './pages/LoginPage';
import AdminPage from './pages/AdminPage';
import MyRunsPage from './pages/MyRunsPage';
import MyCharactersPage from './pages/MyCharactersPage';
import NotesPage from './pages/NotesPage';
import ChangelogPage from './pages/ChangelogPage';
import { lazy, Suspense } from 'react';
import type { ReactNode } from 'react';

// 语音页面用到 livekit-client（体积较大），按需加载，没进语音的人不用下
const VoicePage = lazy(() => import('./pages/VoicePage'));

/**
 * 首页：
 * - 未登录 → 车次一览表
 * - 管理员 → 车次一览表（管理员只管账号和登记角色，不参与「我的车次」）
 * - 群友 → 我的车次
 */
function HomeRoute() {
  const { user } = useAuth();
  if (!user || user.role === 'ADMIN') {
    return <RunsTablePage />;
  }
  return <MyRunsPage />;
}

/**
 * 需要登录才能进的页面。
 *
 * 后端已经把「群友名单 / 我的角色 / 语音」收紧了：匿名访问会直接 401。
 * 界面上也必须跟着挡——否则未登录用户点进这些页只会看到一串报错，
 * 而且第一次 401 还会触发一次没意义的「刷新 token」尝试。
 * 这里统一把未登录用户送回车次一览表（匿名唯一可看的页面）。
 */
function RequireLogin({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  if (!user) {
    return <Navigate to="/runs" replace />;
  }
  return <>{children}</>;
}

export default function App() {
  const { ready, isAdmin, canSchedule } = useAuth();

  if (!ready) {
    return (
      <div className="center">
        <div>
          <div className="loading-bar" style={{ width: 220 }} />
          <p>正在加载…</p>
        </div>
      </div>
    );
  }

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="*"
        element={
          <Layout>
            <Routes>
              {/* 首页：登录后 = 我的车次（只看自己要打的 BOSS），未登录 = 车次一览表 */}
              <Route path="/" element={<HomeRoute />} />
              <Route path="/runs" element={<RunsTablePage />} />
              {/* 排班管理已并入 boss排班 */}
              <Route path="/schedule" element={<Navigate to="/bosses" replace />} />
              <Route path="/bosses" element={canSchedule ? <BossSchedulePage /> : <Navigate to="/runs" replace />} />
              <Route
                path="/voice"
                element={
                  <RequireLogin>
                    <Suspense fallback={<p className="muted">正在加载语音组件…</p>}>
                      <VoicePage />
                    </Suspense>
                  </RequireLogin>
                }
              />
              <Route
                path="/players"
                element={
                  <RequireLogin>
                    <PlayersPage />
                  </RequireLogin>
                }
              />
              <Route path="/notes" element={<RequireLogin><NotesPage /></RequireLogin>} />
              <Route path="/changelog" element={<ChangelogPage />} />
              <Route path="/me" element={<Navigate to="/" replace />} />
              <Route
                path="/my-characters"
                element={
                  <RequireLogin>
                    <MyCharactersPage />
                  </RequireLogin>
                }
              />
              <Route path="/admin" element={isAdmin ? <AdminPage /> : <Navigate to="/runs" replace />} />
              <Route path="*" element={<Navigate to="/runs" replace />} />
            </Routes>
          </Layout>
        }
      />
    </Routes>
  );
}
