import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import type { VoiceRooms } from '../api/types';
import { useAuth } from '../components/AuthContext';
import { useToast } from '../components/Toast';
import { useVoice } from '../components/VoiceContextBase';
import { checkWebRtc } from '../utils/webrtc-check';
import { deafenButtonLabel, deafenButtonTitle, micButtonLabel, micButtonTitle } from '../utils/audio-gain';
import { DeafenedIcon, MicMutedIcon } from '../components/VoiceIcons';
import VolumeControl from '../components/VolumeControl';

/**
 * BOSS 语音频道：3 个固定频道，登录后点一下就能进房说话。
 *
 * 注意：真正的 WebRTC 连接由全局的 VoiceProvider 持有（见 components/VoiceContext.tsx），
 * 不放在这个页面里 —— 否则切到别的页面时组件卸载，语音就断了。
 * 这里只负责「展示频道列表 + 触发进入」。
 */
export default function VoicePage() {
  const { user } = useAuth();
  const { notify } = useToast();
  const { state, join, leave, toggleMic, toggleDeafen, resumeAudio } = useVoice();
  const [data, setData] = useState<VoiceRooms | null>(null);
  /**
   * 浏览器能力自检结果。
   * 手机上「进不去语音」最常见的原因是用了 App 内置浏览器（微信/QQ）或没装证书，
   * 页面顶部直接把原因和解决办法说清楚，比让用户猜强得多。
   */
  const rtc = useState(() => checkWebRtc())[0];
  const [showDetail, setShowDetail] = useState(false);
  const [loading, setLoading] = useState(true);

  const refreshRooms = useCallback(async (showError = false) => {
    try {
      const rooms = await api.voiceRooms();
      setData(rooms);
    } catch (error) {
      if (showError) {
        notify(error instanceof Error ? error.message : '加载语音频道失败', 'error');
      }
    } finally {
      setLoading(false);
    }
  }, [notify]);

  // 首次加载 + 每 5 秒刷新在线情况（多人同时看也能看到彼此）
  useEffect(() => {
    void refreshRooms(true);
    const timer = window.setInterval(() => { void refreshRooms(); }, 5000);
    return () => window.clearInterval(timer);
  }, [refreshRooms]);

  // 进房 / 退房后立刻刷新一次列表，让在线人数马上更新
  useEffect(() => {
    void refreshRooms();
  }, [state?.roomId, refreshRooms]);

  const ready = data?.ready ?? false;

  return (
    <div>
      <div className="page-head">
        <h1>Boss 语音频道</h1>
        <span className="sub">
          进入后即使切到别的页面（比如 boss排班）语音也不会断；左侧边栏会常驻显示语音状态与同频道成员
        </span>
        {state ? <div className="spacer" /> : null}
        {state ? (
          <button className="btn danger" onClick={() => void leave()}>
            退出语音
          </button>
        ) : null}
      </div>

      {!rtc.ok ? (
        <div className="card voice-rtc-warn">
          <div className="card-title">⚠️ 当前浏览器无法语音</div>
          <p style={{ marginTop: 0 }}>{rtc.message}</p>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <button className="btn small" onClick={() => setShowDetail((v) => !v)}>
              {showDetail ? '隐藏排查信息' : '查看排查信息'}
            </button>
            <button
              className="btn small"
              onClick={() => {
                void navigator.clipboard?.writeText(rtc.detail).then(
                  () => notify('已复制环境信息，可发给管理员'),
                  () => notify('复制失败，请手动截图', 'error'),
                );
              }}
            >
              复制环境信息
            </button>
          </div>
          {showDetail ? <pre className="voice-rtc-detail">{rtc.detail}</pre> : null}
        </div>
      ) : null}

      {!ready && !loading ? (
        <div className="card">
          <div className="card-title">语音服务尚未开通</div>
          <p className="muted" style={{ marginTop: 0 }}>
            管理员还没有把 LiveKit 的密钥配好（<code>GMSR_VOICE_ENABLED</code> / <code>GMSR_VOICE_API_KEY</code> /{' '}
            <code>GMSR_VOICE_API_SECRET</code> / <code>GMSR_VOICE_WS_URL</code>）。
          </p>
        </div>
      ) : null}

      <div className="voice-grid">
        {(data?.rooms ?? []).map((room) => {
          const isActive = state?.roomId === room.id;
          // 自己在这个房间时，用本地实时名单覆盖服务端数据（更及时）
          const names = isActive
            ? [`${user?.displayName ?? '我'}（我）`, ...state.participants.map((x) => x.name)]
            : room.online ?? [];
          const count = names.length;
          return (
            <div className={`voice-card ${isActive ? 'active' : ''}`} key={room.id}>
              <div className="voice-icon">{room.icon}</div>
              <div className="voice-name">{room.name}</div>
              <div className="voice-desc muted">{room.description}</div>

              <div className="voice-presence">
                <span className={`voice-count ${count > 0 ? 'on' : ''}`}>
                  {count > 0 ? `🔊 ${count} 人在线` : '暂无人在线'}
                </span>
                {count > 0 ? (
                  <div className="voice-chips">
                    {isActive
                      ? state.participants.map((p) => (
                          <span
                            className={`voice-chip ${p.speaking ? 'speaking' : ''} ${
                              p.micMuted ? 'is-muted' : ''
                            }`}
                            key={p.identity}
                            title={`${p.name}${p.micMuted ? '（已闭麦）' : ''}${
                              p.deafened ? '（已静音，不听队友）' : ''
                            }`}
                          >
                            {p.name}
                            {p.micMuted ? (
                              <em className="vc-flag">
                                <MicMutedIcon />
                              </em>
                            ) : null}
                            {p.deafened ? (
                              <em className="vc-flag">
                                <DeafenedIcon />
                              </em>
                            ) : null}
                            {/* 单独调整这个队友的音量（0~150%），只影响自己的听感 */}
                            <VolumeControl kind="member" trackSid={p.trackSid} name={p.name} compact />
                          </span>
                        ))
                      : names.map((n, i) => (
                          <span className="voice-chip" key={n + i}>
                            {n}
                          </span>
                        ))}
                  </div>
                ) : null}
              </div>

              {isActive ? (
                <>
                  <div className="voice-status">
                    {state?.micMuted ? <MicMutedIcon className="voice-status-icon" /> : null}
                    {state?.deafened ? <DeafenedIcon className="voice-status-icon" /> : null}
                    <span className={`dot ${state?.micMuted ? 'off' : 'on'}`} />
                    {state?.micMuted ? '已闭麦（队友听不到你）' : '麦克风开启中'}
                    {state?.deafened ? ' · 已静音（你听不到队友）' : ''}
                    {/* 调自己的麦克风音量：队友听到的就是这个音量 */}
                    <VolumeControl kind="mic" />
                  </div>

                  {state?.micError ? (
                    <div className="voice-warn">
                      麦克风未能开启：{state.micError}
                      <br />
                      请确认已安装并信任网站证书，且允许了麦克风权限。
                    </div>
                  ) : null}

                  {state?.audioBlocked && !state?.deafened ? (
                    <>
                      <button className="btn primary voice-unsilence" onClick={() => void resumeAudio()}>
                        🔔 点这里开启声音
                      </button>
                      <div className="voice-warn">
                        浏览器拦截了自动播放，所以你现在<strong>听不到别人说话</strong>。点上面的按钮即可开启。
                      </div>
                    </>
                  ) : null}

                  <div className="voice-legend muted">
                    <span className="vl-item">
                      <MicMutedIcon /> 已闭麦（队友听不到他）
                    </span>
                    <span className="vl-item">
                      <DeafenedIcon /> 已静音（他听不到队友）
                    </span>
                    <span className="vl-item">名字变绿 = 正在说话</span>
                    <span className="vl-item">点成员旁的音量可单独调 TA；自己的麦克风音量在旁边单独调</span>
                  </div>
                  <div className="voice-actions">
                    <button
                      className={`btn small ${state?.deafened ? 'danger' : ''}`}
                      onClick={toggleDeafen}
                      title={deafenButtonTitle(Boolean(state?.deafened))}
                    >
                      {deafenButtonLabel(Boolean(state?.deafened))}
                    </button>
                    {/* 麦克风按钮：只显示**当前状态**，点击即切换
                        （开麦中 → 点击闭麦；闭麦中 → 点击开麦） */}
                    <button
                      className={`btn small ${state?.micMuted ? 'danger' : ''}`}
                      onClick={() => void toggleMic()}
                      title={micButtonTitle(Boolean(state?.micMuted))}
                    >
                      {micButtonLabel(Boolean(state?.micMuted))}
                    </button>
                    <button className="btn small danger" onClick={() => void leave()} title="离开语音频道">
                      退出
                    </button>
                  </div>
                </>
              ) : (
                <button
                  className="btn primary"
                  disabled={!ready || !user || state?.connecting === true}
                  onClick={() => void join(room)}
                >
                  {state?.connecting && state.roomId === room.id ? '进入中…' : '进入频道'}
                </button>
              )}
            </div>
          );
        })}
      </div>

      {!user ? (
        <p className="muted" style={{ marginTop: 18 }}>
          请先登录，登录后就能进入语音频道。
        </p>
      ) : null}
      {loading ? <p className="muted">加载中…</p> : null}
    </div>
  );
}
