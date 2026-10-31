import { useEffect, useState } from 'react';
import { NavLink, useNavigate } from 'react-router-dom';
import { useAuth } from './AuthContext';
import { useDevice } from './DeviceContext';
import VoiceWidget from './VoiceWidget';
import PasswordChangeModal from './PasswordChangeModal';

// 菜单只留图标 + 名称：名称已经足够说明用途，不再配一行解释文字。
const NAV = [
  // 车次一览表放第一位：未登录默认展示这一页
  { to: '/runs', label: '车次一览表', icon: '📋' },
  { to: '/', label: '我的车次', icon: '🙋', hideForAdmin: true },
  { to: '/my-characters', label: '我的信息', icon: '🧙', loginOnly: true },
  { to: '/bosses', label: 'boss排班', icon: '🐉', scheduleOnly: true },
  { to: '/voice', label: 'Boss语音频道', icon: '🎙️', loginOnly: true },
  { to: '/players', label: '群友角色一览表', icon: '👥' },
  { to: '/changelog', label: '更新记录', icon: '📝' },
];

/** 首页会根据登录状态切换内容，菜单高亮也要跟着切换。 */
function navClass(target: string, isActive: boolean, user: unknown): string | undefined {
  if (target === '/' && !user) {
    return undefined; // 未登录时首页显示的是「车次一览表」
  }
  if (target === '/runs' && !user) {
    return 'side-link active';
  }
  return isActive ? 'side-link active' : 'side-link';
}

/** 手机端横滑导航的高亮（同上逻辑）。 */
function navClassMobile(target: string, isActive: boolean, user: unknown): string | undefined {
  if (target === '/' && !user) {
    return undefined;
  }
  if (target === '/runs' && !user) {
    return 'active';
  }
  return isActive ? 'active' : undefined;
}

/**
 * 菜单可见性：
 * - 未登录：只显示「车次一览表」（其他功能都要求登录）
 * - 已登录：我的角色要登录、boss排班看排班权限、系统设置仅管理员
 */
function visibleNav(
  user: { role: string; canSchedule?: boolean } | null,
  item: { to: string; adminOnly?: boolean; scheduleOnly?: boolean; loginOnly?: boolean; hideForAdmin?: boolean },
) {
  if (!user) {
    return item.to === '/runs';
  }
  // 管理员不做「我的车次」：他只管账号和帮忙登记角色
  if (item.hideForAdmin && user.role === 'ADMIN') {
    return false;
  }
  // 排班权限：管理员天然有，其他账号看 canSchedule
  const canSchedule = user.role === 'ADMIN' || Boolean(user.canSchedule);
  if (item.scheduleOnly) {
    return canSchedule;
  }
  if (item.adminOnly && user.role !== 'ADMIN') {
    return false;
  }
  return true;
}

export default function Layout({ children }: { children: React.ReactNode }) {
  /** 手机版 / 电脑版切换（自动判断不准时，用户可手动指定） */
  const { isMobile, preference, setPreference } = useDevice();
  const { user, logout, isAdmin, refresh } = useAuth();
  const navigate = useNavigate();
  const [forcePassword, setForcePassword] = useState(false);
  const [showPasswordBanner, setShowPasswordBanner] = useState(false);

  // 首次登录（或管理员刚重置过）强制改密码
  useEffect(() => {
    if (user?.mustChangePassword) {
      setForcePassword(true);
    }
  }, [user?.mustChangePassword]);

  useEffect(() => {
    if (!forcePassword && user?.mustChangePassword) {
      setShowPasswordBanner(true);
    }
  }, [forcePassword, user?.mustChangePassword]);

  return (
    <div className={`app-shell ${isMobile ? 'is-mobile' : 'is-desktop'}`}>
      <aside className="sidebar">
        <div className="sidebar-brand">
          <span className="logo">🌟</span>
          <div>
            GMSR 组队排班
            <small>国际服冒险岛</small>
          </div>
        </div>

        <nav className="side-nav">
          {NAV.filter((item) => visibleNav(user, item)).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => navClass(item.to, isActive, user)}
            >
              <span className="ico">{item.icon}</span>
              <span className="txt">{item.label}</span>
            </NavLink>
          ))}
          {isAdmin ? (
            <NavLink to="/admin" className="side-link">
              <span className="ico">⚙️</span>
              <span className="txt">系统设置</span>
            </NavLink>
          ) : null}
        </nav>

        <VoiceWidget />

        <div className="sidebar-foot">
          {user ? (
            /*
              登录后只显示「名称 + 退出」。
              以前还写了角色（群友（只读））和「绑定 xxx」，但那些对用户没有用：
              名称已经说明是谁，角色与绑定关系都是内部概念，堆在这里只会显得杂乱。
            */
            <div className="who-row">
              <strong className="who-name" title={user.displayName}>
                {user.displayName}
              </strong>
              <button
                className="btn small ghost"
                onClick={() => {
                  logout();
                  navigate('/');
                }}
              >
                退出
              </button>
            </div>
          ) : (
            <>
              <div className="who">
                <strong>未登录</strong>
                <small>当前只能查看车次一览表</small>
              </div>
              <button className="btn small primary" onClick={() => navigate('/login')}>
                去登录
              </button>
            </>
          )}
        </div>
      </aside>

      {forcePassword ? (
        <PasswordChangeModal
          required
          onClose={() => undefined}
          onDone={() => {
            setForcePassword(false);
            setShowPasswordBanner(false);
            refresh();
          }}
        />
      ) : null}

      <div className="main-area">
        <div className="mobile-top">
          <span className="logo">🌟</span>
          <strong>GMSR 组队排班</strong>
          <div className="spacer" />
          {user ? (
            <span className="muted">
              {user.playerNickname && user.playerNickname !== user.displayName
                ? `${user.displayName} · ${user.playerNickname}`
                : user.displayName}
            </span>
          ) : (
            <button className="btn small primary" onClick={() => navigate('/login')}>
              登录
            </button>
          )}
          {/* 自动判断不准时可以手动切回电脑版（手机上表格类页面电脑版更清楚） */}
          <button
            className="btn small ghost"
            title="切换到电脑版界面"
            onClick={() => setPreference(preference === 'desktop' ? 'auto' : 'desktop')}
          >
            {preference === 'desktop' ? '手机版' : '电脑版'}
          </button>
        </div>
        {/*
          切到「电脑版」后，.is-desktop 会把 .mobile-top 整个隐藏 ——
          手机版切换按钮就长在那个顶栏里，于是按钮跟着消失，用户再也切不回来。
          所以这里再放一个常驻的浮动按钮，只在「用户手动选了电脑版」时出现。
        */}
        {preference === 'desktop' ? (
          <button
            className="layout-fab"
            title="切回手机版（恢复按屏幕自动识别）"
            onClick={() => setPreference('auto')}
          >
            📱 手机版
          </button>
        ) : null}
        <nav className="mobile-nav">
          {NAV.filter((item) => visibleNav(user, item)).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/'}
              className={({ isActive }) => navClassMobile(item.to, isActive, user)}
            >
              {item.icon} {item.label}
            </NavLink>
          ))}
          {isAdmin ? <NavLink to="/admin">⚙️ 设置</NavLink> : null}
        </nav>
        {showPasswordBanner && !forcePassword ? (
          <div className="notice">
            ⚠️ 你的密码尚未设置，为了账号安全请尽快
            <button className="link" onClick={() => setForcePassword(true)}>
              修改密码
            </button>
          </div>
        ) : null}
        {/*
          待改密时**不渲染页面内容**。
          服务端现在会拦截待改密账号的其它接口（403 MUST_CHANGE_PASSWORD），
          如果照常挂载页面，它们会并发发一堆请求、弹一堆错误提示。
          这里直接不渲染，用户只看到改密弹窗。
        */}
        <main className="page">{forcePassword ? null : children}</main>
      </div>
    </div>
  );
}
