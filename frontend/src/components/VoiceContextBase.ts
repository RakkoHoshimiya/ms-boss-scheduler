import { createContext, useContext } from 'react';
import type { VoiceRoom } from '../api/types';

/** 正在语音里的状态 */
export interface VoiceState {
  roomId: string;
  roomName: string;
  /** 是否闭麦：不把自己的声音发给别人 */
  micMuted: boolean;
  /** 是否静音：不播放别人的声音（你听不到别人，别人仍能听到你） */
  deafened: boolean;
  /** 麦克风错误信息（拿不到麦克风时提示用户） */
  micError: string | null;
  /** 浏览器拦截了自动播放，需要用户点一下才能听到 */
  audioBlocked: boolean;
  /** 同频道其它成员 */
  participants: Array<{
    identity: string;
    name: string;
    speaking: boolean;
    /** 对方是否闭麦（麦克风静音）—— 由 LiveKit 的音轨状态同步 */
    micMuted: boolean;
    /** 对方是否静音（不听队友）—— 通过 participant attributes 广播 */
    deafened: boolean;
    /** 对方音频轨的 sid，音量按它设置 */
    trackSid: string;
    /** 我为这个队友设置的音量倍率（0 ~ 1.5，1 = 原始） */
    volume: number;
  }>;
  /** 自己麦克风的音量倍率（0 ~ 2，1 = 原始） */
  micVolume: number;
  /** 麦克风增益是否可用（false = 浏览器不支持，音量调不了但麦克风正常） */
  micGainAvailable?: boolean;
  /** 正在连接中 */
  connecting: boolean;
}

export interface VoiceContextValue {
  state: VoiceState | null;
  join: (room: VoiceRoom) => Promise<void>;
  leave: (silent?: boolean) => Promise<void>;
  toggleMic: () => Promise<void>;
  toggleDeafen: () => void;
  resumeAudio: () => Promise<void>;
  /** 调整某个队友对我的音量（0 ~ 1.5）。只影响自己的听感。 */
  setMemberVolume: (trackSid: string, volume: number) => void;
  /** 调整自己麦克风的音量（0 ~ 2）。队友听到的是调过音量的声音。 */
  setMicVolume: (volume: number) => void;
}

/**
 * 只是一个「空壳」上下文对象 + Hook —— 刻意不 import livekit-client。
 *
 * 为什么要拆成两个文件：livekit-client 很大（约 570KB），
 * 但它只在 VoiceContext.tsx（Provider 实现）里用到。
 * 如果 Hook 和 Provider 写在同一个文件里，任何组件只要 import 这个 Hook，
 * 就会把整个 livekit-client 拉进主包（实测首屏从 229KB 涨到 803KB）。
 * 拆开后：主包只带这个几十行的文件，livekit-client 留在独立分包里按需加载。
 */
export const VoiceContext = createContext<VoiceContextValue | null>(null);

/** 未挂载 VoiceProvider 时的安全空实现。 */
const EMPTY_VOICE: VoiceContextValue = {
  state: null,
  join: async () => undefined,
  leave: async () => undefined,
  toggleMic: async () => undefined,
  toggleDeafen: () => undefined,
  resumeAudio: async () => undefined,
  setMemberVolume: () => undefined,
  setMicVolume: () => undefined,
};

/** 读取全局语音状态与方法。 */
export function useVoice(): VoiceContextValue {
  return useContext(VoiceContext) ?? EMPTY_VOICE;
}
