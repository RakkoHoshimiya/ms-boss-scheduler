import { useNavigate } from 'react-router-dom';
import { useVoice } from './VoiceContextBase';
import { deafenButtonLabel, deafenButtonTitle, micButtonLabel, micButtonTitle } from '../utils/audio-gain';
import { DeafenedIcon, MicMutedIcon } from './VoiceIcons';
import VolumeControl from './VolumeControl';

/**
 * 侧栏常驻的语音面板。
 *
 * 放在侧栏而不是语音页面里，是为了：
 * 1. 切到任何页面（比如 boss排班）语音都不会断；
 * 2. 随时能看到同频道有谁、谁在说话，并一键开麦/静音/退出。
 */
export default function VoiceWidget() {
  const { state, leave, toggleMic, toggleDeafen, resumeAudio } = useVoice();
  const navigate = useNavigate();

  if (!state) {
    return null;
  }

  const others = state.participants;

  return (
    <div className="voice-widget">
      <div className="vw-head">
        <span className={`vw-dot ${state.micMuted ? 'muted' : 'live'}`} />
        <button className="vw-name" onClick={() => navigate('/voice')} title="回到语音频道页">
          🎙️ {state.roomName}
        </button>
        <button className="vw-close" onClick={() => void leave()} title="退出语音">
          ✕
        </button>
      </div>

      <div className="vw-status">
        {state.connecting
          ? '连接中…'
          : `${state.micMuted ? '已闭麦' : '麦克风开启中'}${state.deafened ? ' · 已静音' : ''}`}
      </div>

      {state.audioBlocked && !state.deafened ? (
        <button className="btn small primary vw-unblock" onClick={() => void resumeAudio()}>
          🔔 点这里开启声音
        </button>
      ) : null}

      <div className="vw-people">
        <span className="vw-count">在线 {others.length + 1} 人</span>
        <div className="vw-chips">
          <span className="voice-chip me">
            我
            {state.micMuted ? (
              <em className="vc-flag">
                <MicMutedIcon title="我已闭麦" />
              </em>
            ) : null}
            {state.deafened ? (
              <em className="vc-flag">
                <DeafenedIcon title="我已静音（不听队友）" />
              </em>
            ) : null}
          </span>
          {others.map((p) => (
            <span
              className={`voice-chip ${p.speaking ? 'speaking' : ''} ${p.micMuted ? 'is-muted' : ''}`}
              key={p.identity}
              title={`${p.name}${p.micMuted ? '（已闭麦，听不到他说话）' : ''}${
                p.deafened ? '（已静音，他听不到队友）' : ''
              }`}
            >
              {p.name}
              {/* 标识放在名字后面，一眼看出谁闭麦、谁静音 */}
              {/* 闭麦 = 麦克风+斜杠；静音 = 耳机+斜杠。两个形状不同才分得清 */}
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
              <VolumeControl kind="member" trackSid={p.trackSid} name={p.name} compact />
            </span>
          ))}
        </div>
      </div>

      <div className="vw-actions">
        <button
          className={`btn small ${state.deafened ? 'danger' : 'ghost'}`}
          onClick={toggleDeafen}
          title={deafenButtonTitle(state.deafened)}
        >
          {state.deafened ? (
            <>
              <DeafenedIcon size={13} /> {deafenButtonLabel(true)}
            </>
          ) : (
            deafenButtonLabel(false)
          )}
        </button>
        {/* 麦克风按钮：显示当前状态，点击切换 */}
        <button
          className={`btn small ${state.micMuted ? 'danger' : 'ghost'}`}
          onClick={() => void toggleMic()}
          title={micButtonTitle(state.micMuted)}
        >
          {state.micMuted ? (
            <>
              <MicMutedIcon size={13} /> {micButtonLabel(true)}
            </>
          ) : (
            micButtonLabel(false)
          )}
        </button>
      </div>
    </div>
  );
}
