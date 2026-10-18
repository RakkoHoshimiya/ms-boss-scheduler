/**
 * 语音状态图标（内联 SVG）。
 *
 * 为什么不用 emoji：
 *   原来「闭麦」用的是 🔇、「静音」用的是 🔈，这两个都是**喇叭**形状，
 *   放在一起分不清谁是谁 —— 用户反馈「麦克风静音的图标放错了」正是这个原因。
 *   这里用 SVG 手绘，可以精确区分：
 *     · MicMutedIcon   = 麦克风 + 斜杠  → 我的麦克风关了（队友听不到我）
 *     · DeafenedIcon   = 耳机 + 斜杠    → 我不听队友（但队友还能听到我）
 *     · SpeakerIcon    = 喇叭（未静音）
 *
 * 统一 16×16 视口、currentColor 描边，跟随文字颜色，在深色底上清晰。
 */

interface IconProps {
  size?: number;
  className?: string;
  title?: string;
}

/** 麦克风本体（被两个图标复用）。 */
function MicBody() {
  return (
    <>
      {/* 话筒头 */}
      <rect x="9" y="2" width="6" height="11" rx="3" />
      {/* 支架 */}
      <path d="M5.5 10.5a6.5 6.5 0 0 0 13 0" />
      {/* 支脚 */}
      <path d="M12 17v3M8.5 20h7" />
    </>
  );
}

/**
 * 闭麦：麦克风 + 斜杠。
 * 含义 = 我的麦克风被关掉了，队友听不到我说话。
 */
export function MicMutedIcon({ size = 15, className = '', title = '已闭麦' }: IconProps) {
  return (
    <svg
      className={`voice-icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={title}
    >
      <title>{title}</title>
      <MicBody />
      {/* 斜杠：从右上到左下划掉麦克风，这是「静音」的通用符号 */}
      <path className="voice-icon-slash" d="M3 3l18 18" />
    </svg>
  );
}

/**
 * 收音静音（不听队友）：耳机 + 斜杠。
 * 含义 = 我不播放队友的声音，但我说话队友照样听得到 —— 和闭麦完全不同。
 */
export function DeafenedIcon({ size = 15, className = '', title = '已静音（不听队友）' }: IconProps) {
  return (
    <svg
      className={`voice-icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={title}
    >
      <title>{title}</title>
      {/* 耳罩 + 头梁：耳机的通用形状 */}
      <path d="M4 14v-2a8 8 0 0 1 16 0v2" />
      <rect x="2.5" y="13.5" width="4.5" height="7" rx="2" />
      <rect x="17" y="13.5" width="4.5" height="7" rx="2" />
      <path className="voice-icon-slash" d="M3 3l18 18" />
    </svg>
  );
}

/** 喇叭（未静音时用，例如按钮上的「静音」动作）。 */
export function SpeakerIcon({ size = 15, className = '', title = '声音开启' }: IconProps) {
  return (
    <svg
      className={`voice-icon ${className}`}
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={title}
    >
      <title>{title}</title>
      <path d="M11 5 6.5 9H3v6h3.5L11 19z" />
      <path d="M16 8.5a5 5 0 0 1 0 7" />
      <path d="M19 5.5a9 9 0 0 1 0 13" />
    </svg>
  );
}
