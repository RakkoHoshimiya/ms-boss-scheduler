import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api } from '../api/client';

/** 设备类型：服务端按 User-Agent 判断，前端结合视口宽度做最终决定。 */
export type DeviceKind = 'mobile' | 'tablet' | 'desktop';

/** 用户手动选择的界面版本（覆盖自动判断）。 */
export type LayoutPreference = 'auto' | 'mobile' | 'desktop';

const PREF_KEY = 'gmsr.layout';

interface DeviceContextValue {
  /** 最终生效的设备类型 */
  device: DeviceKind;
  /** 是否使用触屏交互（决定排班页用「点选」而不是拖拽） */
  touch: boolean;
  /** 是否使用手机版布局 */
  isMobile: boolean;
  /** 用户手动选择的版本 */
  preference: LayoutPreference;
  /** 切换版本（手机版 / 电脑版 / 自动） */
  setPreference: (pref: LayoutPreference) => void;
  /** 服务端识别到的原始信息（排查问题用） */
  serverDevice: DeviceKind | null;
}

const DeviceContext = createContext<DeviceContextValue | null>(null);

/** 视口够窄就按手机版处理（即使 UA 认不出来，例如部分国产浏览器）。 */
const MOBILE_MAX_WIDTH = 820;

function readPreference(): LayoutPreference {
  const saved = localStorage.getItem(PREF_KEY);
  return saved === 'mobile' || saved === 'desktop' ? saved : 'auto';
}

/**
 * 设备适配。
 *
 * 为什么要做「服务端 UA + 前端视口」双重判断：
 * - 只靠 CSS 媒体查询只能改样式，改不了**交互方式**。排班页原本依赖 HTML5 拖拽，
 *   而手机浏览器不支持拖拽事件 —— 必须切换成「点选 + 点目标」的交互，这需要 JS 知道设备类型。
 * - 只靠前端 UA 容易被改（Chrome 的 UA 精简），所以让服务端也判一次，两边结合更稳。
 * - 视口宽度兜底：UA 认不出来但屏幕确实很窄时，也应该用手机版。
 *
 * 用户还能手动切「电脑版 / 手机版」，偏好存在本地。
 */
export function DeviceProvider({ children }: { children: ReactNode }) {
  const [preference, setPreferenceState] = useState<LayoutPreference>(() => readPreference());
  const [serverDevice, setServerDevice] = useState<DeviceKind | null>(null);
  const [viewportWidth, setViewportWidth] = useState<number>(() =>
    typeof window === 'undefined' ? 1200 : window.innerWidth,
  );

  // 服务端按 UA 判断一次（登录前也能用）
  useEffect(() => {
    api
      .clientInfo()
      .then((info) => setServerDevice(info.device))
      .catch(() => setServerDevice(null));
  }, []);

  // 视口变化要实时跟随（旋转屏幕 / 调整窗口）
  useEffect(() => {
    const onResize = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', onResize);
    window.addEventListener('orientationchange', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      window.removeEventListener('orientationchange', onResize);
    };
  }, []);

  const setPreference = useCallback((pref: LayoutPreference) => {
    setPreferenceState(pref);
    if (pref === 'auto') {
      localStorage.removeItem(PREF_KEY);
    } else {
      localStorage.setItem(PREF_KEY, pref);
    }
  }, []);

  const value = useMemo<DeviceContextValue>(() => {
    // 1) 用户手动指定优先
    // 2) 否则：服务端说是手机，或视口很窄 → 手机版
    let device: DeviceKind;
    if (preference === 'mobile') {
      device = 'mobile';
    } else if (preference === 'desktop') {
      device = 'desktop';
    } else if (serverDevice === 'mobile' || viewportWidth <= MOBILE_MAX_WIDTH) {
      device = 'mobile';
    } else if (serverDevice === 'tablet') {
      device = 'tablet';
    } else {
      device = 'desktop';
    }

    // 触屏判断：手机/平板必然是触屏；桌面即使窄也通常有鼠标
    const touch =
      device !== 'desktop' ||
      (typeof navigator !== 'undefined' && navigator.maxTouchPoints > 0);

    return {
      device,
      touch,
      isMobile: device === 'mobile',
      preference,
      setPreference,
      serverDevice,
    };
  }, [preference, serverDevice, viewportWidth, setPreference]);

  return <DeviceContext.Provider value={value}>{children}</DeviceContext.Provider>;
}

/** 读取设备信息。未包 Provider 时给一个「桌面版」的安全默认值。 */
export function useDevice(): DeviceContextValue {
  return (
    useContext(DeviceContext) ?? {
      device: 'desktop',
      touch: false,
      isMobile: false,
      preference: 'auto',
      setPreference: () => undefined,
      serverDevice: null,
    }
  );
}
