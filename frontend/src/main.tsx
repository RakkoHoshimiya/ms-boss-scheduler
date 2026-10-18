import React, { Suspense, lazy } from 'react';
import ReactDOM from 'react-dom/client';
import { HashRouter } from 'react-router-dom';
import App from './App';
import { AuthProvider } from './components/AuthContext';
import { ToastProvider } from './components/Toast';
import { DeviceProvider } from './components/DeviceContext';
import './styles/app.css';

// 语音相关的代码（livekit-client 体积很大，约 570KB）单独分包懒加载，
// 没进过语音频道的人不需要下载它。
// 注意：VoiceProvider 必须是懒加载的，否则 livekit-client 会被打进主包，首屏体积翻三倍。
const VoiceProvider = lazy(() =>
  import('./components/VoiceContext').then((m) => ({ default: m.VoiceProvider })),
);

/**
 * 冒烟测试跑在 jsdom 里，它无法正确加载多个懒加载分包（会出现重复的 React 实例，
// 导致 useContext 拿到 null）。测试关注的是业务页面，语音面板在测试里也不需要真的连麦，
// 所以由测试脚本设置这个标志，让 Provider 退化成纯透传 —— 语音相关的 hook 会自动
// 落到 VoiceContextBase 里的安全空实现上。
 */
const skipVoiceProvider =
  typeof window !== 'undefined' &&
  (window as unknown as { __GMSR_SKIP_VOICE__?: boolean }).__GMSR_SKIP_VOICE__ === true;

function VoiceShell({ children }: { children: React.ReactNode }) {
  if (skipVoiceProvider) {
    return <>{children}</>;
  }
  return <VoiceProvider>{children}</VoiceProvider>;
}

ReactDOM.createRoot(document.getElementById('root') as HTMLElement).render(
  <React.StrictMode>
    <HashRouter>
      <DeviceProvider>
        <ToastProvider>
          <AuthProvider>
            {/* fallback 用 null：语音是常驻能力，不该在首屏闪一个占位 */}
            <Suspense fallback={null}>
              <VoiceShell>
                <App />
              </VoiceShell>
            </Suspense>
          </AuthProvider>
        </ToastProvider>
      </DeviceProvider>
    </HashRouter>
  </React.StrictMode>,
);
