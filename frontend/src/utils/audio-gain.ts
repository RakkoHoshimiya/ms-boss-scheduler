/**
 * 音频增益（音量调节）核心逻辑。
 *
 * 为什么不用 LiveKit 内置的 setVolume：
 *   LiveKit 的 RemoteAudioTrack.setVolume 有两条路径，且由它自己决定走哪条 ——
 *   开了 webAudioMix 时它会把 <audio> 元素 muted=true、改由内部 GainNode 出声；
 *   一旦那条内部链路没建立起来，结果就是「彻底没声音」。
 *   我们之前在这个功能上连续失败，就是因为无法控制它的内部状态。
 *
 * 这里的做法：**完全自己建音频图**，元素保持正常播放（不 muted）：
 *
 *   远端成员：
 *     <audio>.srcObject → MediaStreamSource → [我的 GainNode] → destination
 *
 *   自己的麦克风：
 *     getUserMedia → MediaStreamSource → [我的 GainNode] → destination.stream
 *     → 把 destination 的输出音轨发布给队友
 *
 * 这样最坏情况只是「音量调不动」（GainNode 没建起来时元素照常按原音量播放），
 * 绝不会出现「没有声音」。
 *
 * 本模块刻意不 import 任何浏览器/React 相关代码，方便用 mock 做单元测试。
 */

/** 远端成员音量的上限：>1 是放大。再高会把对方的底噪一起放大，听感更差。 */
export const MAX_REMOTE_VOLUME = 1.5;

/** 自己麦克风的上限：允许放大到 2 倍（有人麦克风本身很小）。 */
export const MAX_MIC_VOLUME = 2;

/** 取浏览器的 AudioContext 构造器（兼容 Safari 的 webkit 前缀）。 */
export function getAudioContextCtor(): typeof AudioContext | undefined {
  if (typeof window === 'undefined') {
    return undefined;
  }
  const w = window as unknown as {
    AudioContext?: typeof AudioContext;
    webkitAudioContext?: typeof AudioContext;
  };
  return w.AudioContext ?? w.webkitAudioContext;
}

/** 一条远端音轨的音频图。 */
export interface RemoteAudioGraph {
  identity: string;
  source: MediaStreamAudioSourceNode;
  gain: GainNode;
  /** 分析节点：用来检测「这条图是否真的有音频流过」 */
  analyser: AnalyserNode;
  /** 设置音量倍率（0 ~ MAX_REMOTE_VOLUME） */
  setVolume: (volume: number) => void;
  /** 读取当前音量（测试与诊断用） */
  getVolume: () => number;
  /**
   * 检测这条图当前是否在输出声音（看分析节点的能量）。
   *
   * 用途：确认「增益链路真的在工作」之后才敢把 <audio> 元素静音。
   * 否则一旦链路是坏的，元素又静音，就会「完全没声音」。
   */
  isProducingAudio: () => boolean;
  /** 断开并清理 */
  destroy: () => void;
}

/** 把音量限制在合法范围。 */
export function clampVolume(volume: number, max: number): number {
  if (!Number.isFinite(volume)) {
    return 1;
  }
  return Math.max(0, Math.min(max, volume));
}

/**
 * 为一条远端音轨建立增益图，并接到扬声器。
 *
 * @param identity 成员标识（用来说明这条图属于谁）
 * @param stream   <audio> 元素上的 srcObject
 * @param ctx      复用的 AudioContext（多个成员共用一条，省资源）
 * @returns 音频图；无法建立时返回 null（调用方应保持元素正常播放）
 */
export function createRemoteGraph(
  identity: string,
  stream: MediaStream,
  ctx: AudioContext,
  destination: AudioNode,
): RemoteAudioGraph | null {
  try {
    const source = ctx.createMediaStreamSource(stream);
    const gain = ctx.createGain();
    gain.gain.value = 1;
    // 分析节点接在增益之后、输出之前，用来探测有没有声音流过
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 256;
    source.connect(gain);
    gain.connect(analyser);
    analyser.connect(destination);

    let current = 1;
    const samples = new Uint8Array(analyser.fftSize);

    return {
      identity,
      source,
      gain,
      analyser,
      isProducingAudio: () => {
        try {
          analyser.getByteTimeDomainData(samples);
          // 静音的 PCM 全部是 128（中点），有任何偏离就说明有声音
          let peak = 0;
          for (let i = 0; i < samples.length; i++) {
            peak = Math.max(peak, Math.abs(samples[i] - 128));
          }
          return peak > 1;
        } catch {
          return false;
        }
      },
      setVolume: (volume: number) => {
        const v = clampVolume(volume, MAX_REMOTE_VOLUME);
        current = v;
        // 用 setTargetAtTime 平滑过渡，避免拖动滑块时听到「咔」的爆音
        try {
          gain.gain.setTargetAtTime(v, ctx.currentTime, 0.02);
        } catch {
          gain.gain.value = v;
        }
      },
      getVolume: () => current,
      destroy: () => {
        try {
          source.disconnect();
          gain.disconnect();
          analyser.disconnect();
        } catch {
          // 忽略重复断开
        }
      },
    };
  } catch {
    // 建图失败：返回 null，调用方会让元素继续按原音量播放（不会静音）
    return null;
  }
}

/**
 * 自己麦克风的增益处理器。
 *
 * 实现 LiveKit 的 TrackProcessor 契约：LiveKit 会把我们产出的 processedTrack
 * 当成麦克风音轨发布出去，队友听到的就是经过增益的声音。
 *
 * 注意：getUserMedia 仍然请求 echoCancellation / noiseSuppression / autoGainControl，
 * 所以音质处理（回声消除）不受影响 —— 我们只是在后面串了一个增益。
 */
export interface MicGainProcessor {
  name: string;
  init: (opts: { audioContext?: AudioContext; track: MediaStreamTrack }) => Promise<void>;
  restart: (opts: { audioContext?: AudioContext; track: MediaStreamTrack }) => Promise<void>;
  destroy: () => Promise<void>;
  processedTrack?: MediaStreamTrack;
  setVolume: (volume: number) => void;
  getVolume: () => number;
}

export function createMicGainProcessor(): MicGainProcessor {
  let ctx: AudioContext | null = null;
  let source: MediaStreamAudioSourceNode | null = null;
  let gain: GainNode | null = null;
  let dest: MediaStreamAudioDestinationNode | null = null;
  let current = 1;

  const teardown = () => {
    try {
      source?.disconnect();
      gain?.disconnect();
    } catch {
      // 忽略
    }
    source = null;
    gain = null;
    // dest 与 ctx 由实例持有，init 时复用
  };

  const setup = async (opts: { audioContext?: AudioContext; track: MediaStreamTrack }) => {
    const Ctor = getAudioContextCtor();
    if (!Ctor) {
      throw new Error('浏览器不支持 AudioContext，无法调节麦克风音量');
    }
    // 优先复用 LiveKit 传入的 context —— 它已经被浏览器策略解锁过，不会处于 suspended
    ctx = opts.audioContext ?? ctx ?? new Ctor({ latencyHint: 'interactive' });
    if (ctx.state === 'suspended') {
      await ctx.resume().catch(() => undefined);
    }
    if (!dest) {
      dest = ctx.createMediaStreamDestination();
    }
    teardown();
    source = ctx.createMediaStreamSource(new MediaStream([opts.track]));
    gain = ctx.createGain();
    gain.gain.value = current;
    source.connect(gain);
    gain.connect(dest);
    const out = dest.stream.getAudioTracks()[0];
    if (!out) {
      throw new Error('无法创建麦克风增益输出音轨');
    }
    processor.processedTrack = out;
  };

  const processor: MicGainProcessor = {
    name: 'gmsr-mic-gain',
    init: setup,
    restart: setup,
    destroy: async () => {
      teardown();
      processor.processedTrack = undefined;
    },
    setVolume: (volume: number) => {
      current = clampVolume(volume, MAX_MIC_VOLUME);
      if (gain && ctx) {
        try {
          gain.gain.setTargetAtTime(current, ctx.currentTime, 0.02);
        } catch {
          gain.gain.value = current;
        }
      }
    },
    getVolume: () => current,
  };

  return processor;
}

/* ============================================================
 * 音频跟随决策（纯函数，可单元测试）
 *
 * 背景：createMediaStreamSource() 不会阻止 <audio> 元素继续播放，
 * 所以建了增益图之后必须把元素静音，声音才会只从增益节点出来。
 * 但又不能无条件静音 —— 万一增益图是坏的，静音就等于「完全没声音」。
 *
 * 因此用一个状态机来决定「元素该不该静音」，决策规则集中在下面这个纯函数里，
 * 这样「调到 0% 还能听到队友」这类问题可以被自动化测试覆盖。
 * ============================================================ */

export interface AudioFollowState {
  /** 跟随开始的时间戳（毫秒） */
  startedAt: number;
  /** 是否曾经检测到有音频流过 */
  sawAudio: boolean;
  /** 连续静默的检测次数 */
  silentStreak: number;
}

export interface AudioFollowInput {
  state: AudioFollowState;
  /** 当前时间戳（毫秒） */
  now: number;
  /** 增益图当前是否在输出声音 */
  audible: boolean;
  /** 用户为该成员设定的音量（0 表示明确要求听不到） */
  wantedVolume: number;
  /** 宽限期（毫秒）：这段时间内没听到声音也保持静音，因为对方可能只是没说话 */
  graceMs?: number;
  /** 连续静默多少次后判定链路失效 */
  silentStreakLimit?: number;
}

export type AudioFollowAction =
  /** 保持/设为元素静音，让声音只走增益节点 */
  | { element: 'muted'; next: AudioFollowState }
  /** 恢复元素直接播放（保证至少能听到） */
  | { element: 'audible'; next: AudioFollowState; reason: string }
  /** 图已不存在，跟随结束 */
  | { element: 'stop'; next: AudioFollowState; reason: string };

/**
 * 根据检测结果决定元素的静音状态。
 *
 * 规则（按优先级）：
 *   1. 用户在界面上把这个队友调到了 0 → 元素必须静音（这是他明确的要求，不能因为
 *      「没听到声音」就把元素放出来，否则调 0 又会听到声音）。
 *   2. 检测到有音频 → 保持静音（增益链路确实在工作）。
 *   3. 宽限期内没听到 → 保持静音（对方只是还没说话）。
 *   4. 宽限期结束仍从未听到过 → 无法确认链路可用，恢复元素出声（宁可音量不生效，
 *      也不能让用户听不到队友）。
 *   5. 曾经听到过、现在持续静默 → 判定链路失效，恢复元素出声。
 */
export function decideAudioFollow(input: AudioFollowInput): AudioFollowAction {
  const { state, now, audible, wantedVolume } = input;
  const graceMs = input.graceMs ?? 2500;
  const silentStreakLimit = input.silentStreakLimit ?? 5;

  // 1) 用户明确要求静音这个队友
  if (wantedVolume <= 0.001) {
    return {
      element: 'muted',
      next: { ...state, sawAudio: state.sawAudio, silentStreak: 0 },
    };
  }

  // 2) 有音频流过：增益链路可用，保持元素静音
  if (audible) {
    return {
      element: 'muted',
      next: { startedAt: state.startedAt, sawAudio: true, silentStreak: 0 },
    };
  }

  // 3) 宽限期内（对方可能只是还没说话）
  if (!state.sawAudio && now - state.startedAt < graceMs) {
    return { element: 'muted', next: state };
  }

  // 4) 一直没听到过声音：无法确认链路可用 → 恢复元素，保证能听到
  if (!state.sawAudio) {
    return {
      element: 'audible',
      next: state,
      reason: '宽限期内未检测到音频，无法确认增益链路可用',
    };
  }

  // 5) 曾经听到过、现在持续静默 → 链路可能失效
  const silentStreak = state.silentStreak + 1;
  if (silentStreak >= silentStreakLimit) {
    return {
      element: 'audible',
      next: { ...state, silentStreak: 0 },
      reason: '增益链路持续无声，判定失效',
    };
  }
  return { element: 'muted', next: { ...state, silentStreak } };
}

/* ============================================================
 * 语音按钮的状态文案（纯函数，可单元测试）
 *
 * 这类「状态 vs 动作」的文案很容易写反 —— 之前就出现过
 * 「闭麦后按钮写开麦」让人看不出当前状态、以及图标与状态不一致的问题。
 * 所以把映射集中到这里并用测试锁死。
 * ============================================================ */

/** 麦克风按钮：micMuted 为 true 表示已闭麦（队友听不到你）。 */
export function micButtonLabel(micMuted: boolean): string {
  return micMuted ? '闭麦中' : '开麦中';
}

/** 麦克风按钮的悬停提示，说明当前状态以及点击后会发生什么。 */
export function micButtonTitle(micMuted: boolean): string {
  return micMuted
    ? '当前闭麦中（队友听不到你），点一下开麦'
    : '当前开麦中（队友能听到你），点一下闭麦';
}

/**
 * 静音（不听队友）按钮：也显示**当前状态**而不是动作。
 *   deafened = false → 正在收音，能听到队友 → 「收音中」，点击则静音
 *   deafened = true  → 已把队友声音全部静音     → 「已静音」，点击则收音
 */
export function deafenButtonLabel(deafened: boolean): string {
  return deafened ? '已静音' : '收音中';
}

/** 静音按钮的悬停提示，说明当前状态与点击后果。 */
export function deafenButtonTitle(deafened: boolean): string {
  return deafened
    ? '当前已静音（听不到队友），点一下恢复收音'
    : '当前收音中（能听到队友），点一下静音所有队友';
}
