import { useEffect, useRef, useState } from 'react';
import { useVoice } from './VoiceContextBase';
import { MAX_MIC_VOLUME, MAX_REMOTE_VOLUME, clampVolume } from '../utils/audio-gain';

/** 把倍率显示成百分比：1 → 100% */
export function volumeLabel(volume: number): string {
  return Math.round(volume * 100) + '%';
}

/**
 * 音量图标（SVG，随音量大小变化声波数量）。
 * 不用 emoji：形状统一、能跟随文字颜色、和语音状态图标是一套。
 */
function VolumeIcon({ volume, muted }: { volume: number; muted?: boolean }) {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M11 5 6.5 9H3v6h3.5L11 19z" />
      {!muted && volume > 0.05 ? <path d="M15.5 9a4.5 4.5 0 0 1 0 6" /> : null}
      {!muted && volume > 0.7 ? <path d="M18.5 6.5a8.5 8.5 0 0 1 0 11" /> : null}
      {muted || volume <= 0.05 ? <path d="M3 3l18 18" /> : null}
    </svg>
  );
}

/**
 * 音量调节控件。
 *
 * 两种用法：
 *   - kind="member"：调某个队友对我的音量（0~150%），只影响自己的听感
 *   - kind="mic"   ：调自己麦克风的音量（0~200%），队友听到的就是这个音量
 *
 * 交互做成「按钮 + 浮层」：平时只占一个按钮的位置（侧栏也能放下），
 * 点开才出现滑块。拖动时实时生效，不用点确认。
 */
export default function VolumeControl({
  kind,
  /** kind="member" 时必填：该队友的音轨标识 */
  trackSid,
  /** kind="member" 时必填：显示在提示里的名字 */
  name,
  /** 紧凑模式（侧栏）：按钮不显示百分比数字 */
  compact = false,
}: {
  kind: 'member' | 'mic';
  trackSid?: string;
  name?: string;
  compact?: boolean;
}) {
  const { state, setMemberVolume, setMicVolume } = useVoice();
  const [open, setOpen] = useState(false);
  const boxRef = useRef<HTMLSpanElement | null>(null);

  const isMic = kind === 'mic';
  const max = isMic ? MAX_MIC_VOLUME : MAX_REMOTE_VOLUME;
  const volume = isMic
    ? state?.micVolume ?? 1
    : state?.participants.find((p) => p.trackSid === trackSid)?.volume ?? 1;
  const changed = Math.abs(volume - 1) > 0.001;
  const who = isMic ? '我的麦克风' : name ?? '队友';

  // 点浮层外面就收起，避免一直占着界面
  useEffect(() => {
    if (!open) {
      return;
    }
    const onDocPointerDown = (e: PointerEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('pointerdown', onDocPointerDown);
    return () => document.removeEventListener('pointerdown', onDocPointerDown);
  }, [open]);

  const apply = (v: number) => {
    const next = clampVolume(v, max);
    if (isMic) {
      setMicVolume(next);
    } else if (trackSid) {
      setMemberVolume(trackSid, next);
    }
  };

  // 没有音轨（对方还没发声音轨）时不显示控件
  if (!isMic && !trackSid) {
    return null;
  }

  return (
    <span className={`vol-box${compact ? ' compact' : ''}`} ref={boxRef}>
      <button
        type="button"
        className={`vol-btn${changed ? ' changed' : ''}`}
        title={`${who} 音量：${volumeLabel(volume)}（点此调整，最高 ${Math.round(max * 100)}%）`}
        onClick={(e) => {
          e.stopPropagation();
          setOpen((v) => !v);
        }}
      >
        <VolumeIcon volume={volume} />
        {compact ? null : <span className="vol-num">{volumeLabel(volume)}</span>}
      </button>

      {open ? (
        <span className="vol-pop" onClick={(e) => e.stopPropagation()}>
          <span className="vol-head">
            {who}
            <b>{volumeLabel(volume)}</b>
          </span>
          <input
            type="range"
            min="0"
            max={max}
            step="0.05"
            value={volume}
            aria-label={`${who} 音量`}
            onChange={(e) => apply(Number(e.target.value))}
          />
          <span className="vol-actions">
            <button type="button" className="vol-mini" onClick={() => apply(0)}>
              静音
            </button>
            <button type="button" className="vol-mini" onClick={() => apply(1)}>
              100%
            </button>
            <button
              type="button"
              className="vol-mini strong"
              title={isMic ? '把自己的声音放大到 200%' : '把这个队友放大到 150%'}
              onClick={() => apply(max)}
            >
              {Math.round(max * 100)}%
            </button>
          </span>
        </span>
      ) : null}
    </span>
  );
}
