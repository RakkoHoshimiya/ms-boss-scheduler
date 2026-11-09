import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import {
  LocalAudioTrack,
  Room,
  RoomEvent,
  Track,
  createLocalAudioTrack,
  type LocalTrackPublication,
  type RemoteAudioTrack,
  type RemoteParticipant,
  type RemoteTrack,
} from 'livekit-client';
import { api } from '../api/client';
import { VoiceContext, type VoiceContextValue, type VoiceState } from './VoiceContextBase';
import type { VoiceRoom } from '../api/types';
import { useAuth } from './AuthContext';
import { checkWebRtc } from '../utils/webrtc-check';
import {
  MAX_MIC_VOLUME,
  createMicGainProcessor,
  type MicGainProcessor,
} from '../utils/audio-gain';
import { useToast } from './Toast';


/**
 * 全局语音上下文。
 *
 * 关键点：WebRTC 连接**必须放在路由之外**。
 * 如果放在「Boss语音频道」页面里，一旦切到别的页面组件就被卸载、连接随之断开。
 * 放在这里后，切页 / 点任何菜单语音都不会断，侧栏也能常驻显示语音状态。
 */
export function VoiceProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const { notify } = useToast();
  const [state, setState] = useState<VoiceState | null>(null);

  /** 当前的 Room 实例（不放进 state，避免无谓重渲染） */
  const roomRef = useRef<Room | null>(null);
  /** 已挂到 DOM 的远端音频元素：trackSid -> element */
  const audioElements = useRef(new Map<string, HTMLMediaElement>());
  /** 已挂上的远端音轨：trackSid -> track */
  const remoteTracks = useRef(new Map<string, RemoteAudioTrack>());
  const deafenedRef = useRef(false);
  /**
   * 音频增益相关的资源。
   *
   * 为什么自己建图：LiveKit 内置的 setVolume 在开启 webAudioMix 时会把 <audio> 元素
   * muted=true 并改由它内部的 GainNode 出声，一旦那条内部链路没建好就是「彻底没声音」。
   * 这里我们自己建图，**元素始终保持正常播放**，最坏情况只是音量调不动。
   */
  /** 麦克风增益处理器（发布前给采集到的声音加增益） */
  const micProcessorRef = useRef<MicGainProcessor | null>(null);
  /** 是否已挂上「等待用户手势解锁 AudioContext」的监听 */
  const unlockListenerRef = useRef(false);
  /** 我为每个成员设置的音量倍率（0 ~ 1.5） */
  const memberVolumesRef = useRef(new Map<string, number>());
  /** 自己麦克风的音量倍率（0 ~ 2） */
  const micVolumeRef = useRef(1);
  /** 麦克风增益处理器初始化失败的原因（为空表示增益可用） */
  const micGainErrorRef = useRef<string | null>(null);

  /** 避免并发进入同一个频道 */
  const joiningRef = useRef(false);

  /** 把「静音」状态应用到所有远端音轨（音量归零，但不断开，说话指示灯仍会亮） */
  const applyDeafen = useCallback((deafened: boolean) => {
    remoteTracks.current.forEach((track) => {
      try {
        track.setVolume(deafened ? 0 : 1);
      } catch {
        // 个别浏览器可能不支持，忽略
      }
    });
  }, []);


  const detachAll = useCallback(() => {
    audioElements.current.forEach((element) => element.remove());
    audioElements.current.clear();
    remoteTracks.current.clear();
    // 兜底：清掉任何残留的音频元素
    document.querySelectorAll('audio[data-gmsr-voice]').forEach((el) => el.remove());
  }, []);

  /** 更新 state 中的部分字段（保持 roomId 等不变） */
  const patch = useCallback((changes: Partial<VoiceState>) => {
    setState((prev) => (prev ? { ...prev, ...changes } : prev));
  }, []);

  /**
   * 解锁 AudioContext —— 音量功能能否生效的关键。
   *
   * AudioContext 处于 suspended 时，GainNode 的改动不会产生任何声音效果
   * （增益值确实改了，但链路是死的）。这是「调了没反应」的根因。
   *
   *   1. 进频道后立刻 resume（点击「进入频道」本身就是用户手势）
   *   2. 若被策略拒绝，挂一次性全局手势监听兜底
   *   3. 每次调音量前再 resume 一次
   */
  const resumeAudioContext = useCallback(async (): Promise<boolean> => {
    const room = roomRef.current;
    const ctx = (room as unknown as { audioContext?: AudioContext } | null)?.audioContext;
    if (!ctx) {
      return false;
    }
    if (String(ctx.state) === "running") {
      return true;
    }
    try {
      await ctx.resume();
    } catch {
      // 被策略拒绝，下面挂手势监听兜底
    }
    if (String(ctx.state) === "running") {
      return true;
    }
    if (!unlockListenerRef.current) {
      const unlock = () => {
        void ctx.resume().catch(() => undefined);
        document.removeEventListener("pointerdown", unlock);
        document.removeEventListener("keydown", unlock);
        unlockListenerRef.current = false;
      };
      document.addEventListener("pointerdown", unlock);
      document.addEventListener("keydown", unlock);
      unlockListenerRef.current = true;
    }
    return false;
  }, []);

  /**
   * 调整某个队友对我的音量（0 ~ 1.5）。
   * 走 LiveKit 内置的 webAudioMix 增益链路（成熟语音软件的通用做法）：
   * GainNode 支持 >1 放大，调到 0 即完全静音。
   */
  const setMemberVolume = useCallback((trackSid: string, volume: number) => {
    memberVolumesRef.current.set(trackSid, volume);
    // 改增益前先确保 AudioContext 是活的，否则改了也没有声音效果
    void resumeAudioContext().then((running) => {
      if (!running) {
        console.info('[语音音量] AudioContext 尚未解锁，请点一下页面任意位置');
      }
    });
    const track = remoteTracks.current.get(trackSid);
    if (track) {
      try {
        track.setVolume(volume);
      } catch (e) {
        console.warn('[语音音量] setVolume 失败', e);
      }
    }
    // 同步到界面
    setState((prev) =>
      prev
        ? {
            ...prev,
            participants: prev.participants.map((x) =>
              x.trackSid === trackSid ? { ...x, volume } : x,
            ),
          }
        : prev,
    );
  }, []);

  /**
   * 调整自己麦克风的音量（0 ~ 2）。
   * 通过发布前的增益处理器实现 —— 队友听到的是调过音量的声音。
   */
  const setMicVolume = useCallback((volume: number) => {
    const v = Math.max(0, Math.min(MAX_MIC_VOLUME, volume));
    micVolumeRef.current = v;
    micProcessorRef.current?.setVolume(v);
    patch({ micVolume: v });
  }, [patch]);

  const leave = useCallback(async (silent = false) => {
    const room = roomRef.current;
    roomRef.current = null;
    deafenedRef.current = false;
    micProcessorRef.current = null;
    setState(null);
    detachAll();
    if (room) {
      await room.disconnect().catch(() => undefined);
      if (!silent) {
        notify('已离开语音频道');
      }
    }
  }, [detachAll, notify]);

  // 退出登录时断开语音（避免用着别人的身份留在频道里）
  useEffect(() => {
    if (!user) {
      void leave(true);
    }
  }, [user, leave]);

  // 页面关闭时断开
  useEffect(() => {
    const onUnload = () => {
      void roomRef.current?.disconnect().catch(() => undefined);
    };
    window.addEventListener('beforeunload', onUnload);
    return () => window.removeEventListener('beforeunload', onUnload);
  }, []);

  const join = useCallback(async (room: VoiceRoom) => {
    if (!user) {
      notify('请先登录', 'error');
      return;
    }
    if (joiningRef.current) {
      return;
    }
    /**
     * 进房前先检查浏览器是否具备语音通话能力。
     * 不检查的话，LiveKit 会抛一句英文的「doesn't seem to be supported」，
     * 用户完全不知道是「用了 App 内置浏览器」还是「没装证书」还是「浏览器太旧」。
     */
    const rtc = checkWebRtc();
    if (!rtc.ok) {
      notify(rtc.message, 'error');
      setState(null);
      return;
    }
    // 已经在同一个频道就不用重复进
    if (roomRef.current && state?.roomId === room.id) {
      return;
    }
    joiningRef.current = true;
    setState({
      roomId: room.id,
      roomName: room.name,
      micMuted: true,
      deafened: false,
      micError: null,
      audioBlocked: false,
      participants: [],
      micVolume: micVolumeRef.current,
      connecting: true,
    });
    try {
      // 换频道：先断开旧的
      if (roomRef.current) {
        await leave(true);
        setState({
          roomId: room.id,
          roomName: room.name,
          micMuted: true,
          deafened: false,
          micError: null,
          audioBlocked: false,
          participants: [],
          micVolume: micVolumeRef.current,
          connecting: true,
        });
      }
      const credential = await api.voiceToken(room.id);
      const lkRoom = new Room({
        adaptiveStream: false,
        dynacast: false,
        /**
         * 远端音频走 Web Audio 增益节点 —— 成熟语音软件（Discord / KOOK / OOPZ）的通用做法。
         *
         * 为什么必须开：HTMLMediaElement.volume 上限是 1（无法把队友调大），
         * 且在 iOS Safari 上不生效（livekit/client-sdk-js#537）。
         * 只有 GainNode 才能真正改变某个成员的声音大小。
         *
         * 开启后 LiveKit 会把 <audio> 元素静音、改由它的增益节点出声，
         * 因此不再启用自建的音频图（两套会抢同一条通路）。
         */
        webAudioMix: true,
        audioCaptureDefaults: {
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      const refreshAudioState = () => {
        setState((prev) => (prev ? { ...prev, audioBlocked: !lkRoom.canPlaybackAudio } : prev));
      };

      const syncParticipants = () => {
        setState((prev) =>
          prev
            ? {
                ...prev,
                participants: [...lkRoom.remoteParticipants.values()].map((p: RemoteParticipant) => ({
                  identity: p.identity,
                  name: p.name || p.identity,
                  speaking: p.isSpeaking,
                  // 麦克风是否静音：LiveKit 会把音轨的 muted 状态同步给所有人
                  micMuted: !p.isMicrophoneEnabled,
                  // 是否在「静音（不听队友）」：这是本系统的自定义状态，通过 attributes 广播
                  deafened: p.attributes?.deafened === '1',
                  // 音轨标识：音量是按音轨（trackSid）设置的
                  trackSid:
                    [...p.trackPublications.values()]
                      .find((pub) => pub.track?.kind === Track.Kind.Audio)?.trackSid ?? '',
                  volume: memberVolumesRef.current.get(
                    [...p.trackPublications.values()]
                      .find((pub) => pub.track?.kind === Track.Kind.Audio)?.trackSid ?? '',
                  ) ?? 1,
                })),
              }
            : prev,
        );
      };

      /**
       * 远端音轨必须 attach 成 <audio> 才会出声；
       * 浏览器还可能因「没有用户手势」拦截自动播放，这时提示用户点一下。
       */
      const attachTrack = (track: RemoteTrack) => {
        if (track.kind !== Track.Kind.Audio) {
          return;
        }
        const audioTrack = track as RemoteAudioTrack;
        const key = track.sid ?? '';
        if (remoteTracks.current.has(key)) {
          applyDeafen(deafenedRef.current);
          return;
        }
        const element = audioTrack.attach();
        element.autoplay = true;
        element.setAttribute('data-gmsr-voice', '1');
        element.style.display = 'none';
        document.body.appendChild(element);
        audioElements.current.set(key, element);
        remoteTracks.current.set(key, audioTrack);
        // 音量交给 LiveKit 内置的 webAudioMix 增益链路（行业标准做法）。
        // 不再自建音频图 —— 两套会抢同一条音频通路，表现就是「调了没效果」。
        const savedVolume = memberVolumesRef.current.get(key);
        if (savedVolume !== undefined) {
          try {
            audioTrack.setVolume(savedVolume);
          } catch {
            // 忽略
          }
        }
        applyDeafen(deafenedRef.current);
        void lkRoom.startAudio().then(refreshAudioState).catch(() => {
          setState((prev) => (prev ? { ...prev, audioBlocked: true } : prev));
        });
      };

      const detachTrack = (track: RemoteTrack) => {
        const key = track.sid ?? '';
        const element = audioElements.current.get(key);
        if (element) {
          element.remove();
          audioElements.current.delete(key);
        }
        remoteTracks.current.delete(key);
        track.detach();
        refreshAudioState();
      };

      /** 把房间里已有的远端音频轨全部挂上（先于我们进房的情况） */
      const attachExisting = () => {
        lkRoom.remoteParticipants.forEach((participant: RemoteParticipant) => {
          participant.trackPublications.forEach((publication) => {
            if (publication.track?.kind === Track.Kind.Audio) {
              attachTrack(publication.track);
            }
          });
        });
      };

      lkRoom
        .on(RoomEvent.ParticipantConnected, syncParticipants)
        // 对方切换「静音（不听队友）」时会广播自定义属性，这里要跟着刷新标识
        .on(RoomEvent.ParticipantAttributesChanged, syncParticipants)
        .on(RoomEvent.ParticipantDisconnected, syncParticipants)
        .on(RoomEvent.TrackSubscribed, (track) => {
          attachTrack(track);
          syncParticipants();
        })
        // 对方闭麦 / 开麦时，音轨的 muted 状态会变化，需要刷新标识
        .on(RoomEvent.TrackMuted, syncParticipants)
        .on(RoomEvent.TrackUnmuted, syncParticipants)
        .on(RoomEvent.TrackUnsubscribed, (track) => {
          detachTrack(track);
          syncParticipants();
        })
        .on(RoomEvent.AudioPlaybackStatusChanged, refreshAudioState)
        .on(RoomEvent.TrackPublished, () => {
          attachExisting();
          syncParticipants();
        })
        .on(RoomEvent.ActiveSpeakersChanged, (speakers) => {
          const speaking = new Set(speakers.map((s) => s.identity));
          setState((prev) =>
            prev
              ? {
                  ...prev,
                  participants: prev.participants.map((p) => ({
                    ...p,
                    speaking: speaking.has(p.identity),
                  })),
                }
              : prev,
          );
        })
        .on(RoomEvent.Disconnected, () => {
          detachAll();
          roomRef.current = null;
          deafenedRef.current = false;
          setState(null);
          notify('与语音服务器的连接已断开，请重新进入');
        });

      // LiveKit JS 客户端会在 wsUrl 后自动追加 /rtc（即 wss://host:4433/lk/rtc），
      // nginx 侧把 /lk 前缀剥掉后转发给 LiveKit，所以这里正常 connect 即可。
      await lkRoom.connect(credential.wsUrl, credential.token);
      roomRef.current = lkRoom;
      // 进房先广播自己的初始状态（默认闭麦、未静音），
      // 这样队友一进来就能看到正确标识，不用等我切换一次
      void lkRoom.localParticipant.setAttributes({ muted: '1', deafened: '0' }).catch(() => undefined);
      attachExisting();
      syncParticipants();

      // 开麦：浏览器会弹出麦克风授权
      //
      // 分两步尝试，确保「麦克风一定能开起来」：
      //   ① 带增益处理器创建（这样队友音量可调）
      //   ② ①失败就退回普通音轨（音量不可调，但麦克风正常）
      // 之前只有①，处理器一旦初始化失败就整个麦克风开不起来，用户得手动点「开麦」。
      try {
        let track: Awaited<ReturnType<typeof createLocalAudioTrack>> | null = null;
        try {
          const micProcessor = createMicGainProcessor();
          micProcessorRef.current = micProcessor;
          track = await createLocalAudioTrack({ processor: micProcessor });
        } catch (processorError) {
          // 处理器失败：退回普通音轨，并记下原因（界面可提示「麦克风音量不可调」）
          micProcessorRef.current = null;
          micGainErrorRef.current =
            processorError instanceof Error ? processorError.message : String(processorError);
          console.warn('[语音] 麦克风增益处理器初始化失败，退回普通音轨：', processorError);
          track = await createLocalAudioTrack();
        }
        await lkRoom.localParticipant.publishTrack(track);
        await lkRoom.startAudio();
        // 关键：立刻解锁 AudioContext，否则音量调节不会有任何效果
        await resumeAudioContext();
        setState((prev) =>
          prev
            ? {
                ...prev,
                micMuted: false,
                micError: null,
                // 增益不可用时告知界面，让用户知道「麦克风音量调不了」而不是以为坏了
                micGainAvailable: micProcessorRef.current !== null,
                connecting: false,
              }
            : prev,
        );
        notify(`已进入「${room.name}」，可以说话了`);
      } catch (micError) {
        setState((prev) =>
          prev
            ? {
                ...prev,
                micMuted: true,
                connecting: false,
                micError: micError instanceof Error ? micError.message : '麦克风不可用',
              }
            : prev,
        );
        notify('已进入频道，但麦克风没有开起来（大概率是证书未信任或没授权）', 'error');
      }
    } catch (error) {
      roomRef.current = null;
      setState(null);
      notify(error instanceof Error ? error.message : '进入语音频道失败', 'error');
    } finally {
      joiningRef.current = false;
    }
  }, [applyDeafen, detachAll, leave, notify, resumeAudioContext, state?.roomId, user]);

  /** 闭麦 / 开麦：控制自己是否把声音发给别人 */
  const toggleMic = useCallback(async () => {
    const room = roomRef.current;
    const current = state;
    if (!room || !current) {
      return;
    }
    const participant = room.localParticipant;
    const publication = participant.getTrackPublication(Track.Source.Microphone) as
      | LocalTrackPublication
      | undefined;
    if (current.micMuted) {
      try {
        if (!publication || !publication.track) {
          const track = await createLocalAudioTrack();
          await participant.publishTrack(track);
        } else {
          await (publication.track as LocalAudioTrack).unmute();
        }
        await room.startAudio();
        patch({ micMuted: false, micError: null });
        void room.localParticipant.setAttributes({ muted: '0' }).catch(() => undefined);
        notify('已开麦，队友能听到你了');
      } catch (error) {
        notify(error instanceof Error ? error.message : '开麦失败', 'error');
      }
    } else {
      if (publication?.track) {
        await (publication.track as LocalAudioTrack).mute();
      }
      patch({ micMuted: true });
      // 广播出去，队友那边就能看到「闭麦」标识
      void room.localParticipant.setAttributes({ muted: '1' }).catch(() => undefined);
      notify('已闭麦，队友听不到你了');
    }
  }, [notify, patch, state]);

  /** 静音 / 取消静音：控制自己是否播放别人的声音 */
  const toggleDeafen = useCallback(() => {
    const next = !deafenedRef.current;
    deafenedRef.current = next;
    applyDeafen(next);
    patch({ deafened: next });
    // 广播「我在静音」，队友能看到标识（别人照样能听到你，只是你听不到别人）
    const room = roomRef.current;
    if (room) {
      void room.localParticipant
        .setAttributes({ deafened: next ? '1' : '0', muted: room.localParticipant.isMicrophoneEnabled ? '0' : '1' })
        .catch(() => undefined);
    }
    notify(next ? '已静音：听不到队友声音' : '已取消静音，恢复声音');
  }, [applyDeafen, notify, patch]);


/**
 * 语音音量诊断。排障时在浏览器控制台执行 __GMSR_VOICE_DIAG__()。
 *
 * 开启 webAudioMix 后音量由 LiveKit 内部的 GainNode 承担，所以这里检查：
 *   AudioContext 是否 running（未解锁则音量无效）、每条音轨是否挂上了增益节点。
 */
const voiceDiag = useCallback(() => {
  const room = roomRef.current;
  const ctx = (room as unknown as { audioContext?: AudioContext } | null)?.audioContext;
  const rows = [...remoteTracks.current.entries()].map(([key, track]) => {
    const el = audioElements.current.get(key);
    const t = track as unknown as { gainNode?: GainNode; sourceNode?: MediaStreamAudioSourceNode };
    const participant = state?.participants.find((x) => x.trackSid === key);
    return {
      成员: participant?.name ?? key,
      设定音量: memberVolumesRef.current.get(key) ?? 1,
      增益链路: t.gainNode && t.sourceNode ? "正常" : "缺失（该成员音量不可调）",
      增益实际值: t.gainNode ? Number(t.gainNode.gain.value.toFixed(2)) : null,
      元素静音: el ? el.muted : null,
    };
  });
  const acState = ctx ? String(ctx.state) : "未创建";
  console.info("======= 语音音量诊断 =======");
  console.table(rows);
  console.info("AudioContext:", acState, acState !== "running" ? "← 未解锁！音量调节不会生效，请点一下页面" : "（已解锁，音量可生效）");
  console.info("麦克风增益:", micProcessorRef.current ? "可用" : "不可用（" + (micGainErrorRef.current ?? "未开启或已降级") + "）");
  return rows;
}, [state?.participants]);

/** 被自动播放策略拦住时，手动恢复播放 */
  const resumeAudio = useCallback(async () => {
    const room = roomRef.current;
    if (!room) {
      return;
    }
    try {
      await room.startAudio();
      patch({ audioBlocked: false });
    } catch {
      notify('还是没能开启声音，试试点一下页面任意位置', 'error');
    }
  }, [notify, patch]);

  const value = useMemo<VoiceContextValue>(
    () => {
      if (typeof window !== 'undefined') {
        (window as unknown as Record<string, unknown>).__GMSR_VOICE_DIAG__ = voiceDiag;
      }
      return { state, join, leave, toggleMic, toggleDeafen, resumeAudio, setMemberVolume, setMicVolume };
    },
    [state, join, leave, toggleMic, toggleDeafen, resumeAudio, setMemberVolume, setMicVolume, voiceDiag, resumeAudioContext],
  );

  return <VoiceContext.Provider value={value}>{children}</VoiceContext.Provider>;
}

// 对外重新导出，调用方既可以 import { useVoice } from './VoiceContext'，
// 也可以（更推荐）从 './VoiceContextBase' 引入以避免带上 livekit-client。
export { useVoice } from './VoiceContextBase';
export type { VoiceState, VoiceContextValue } from './VoiceContextBase';
