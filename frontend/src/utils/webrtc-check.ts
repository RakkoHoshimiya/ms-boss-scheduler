/**
 * 浏览器 WebRTC 能力检测。
 *
 * 背景：手机上点「进入频道」时报
 *   LiveKit doesn't seem to be supported on this browser.
 * 这是 LiveKit 的 isBrowserSupported() 判断失败，它的条件是：
 *   typeof RTCPeerConnection !== 'undefined' && (支持 RTCRtpTransceiver 或 addTrack)
 *
 * 失败原因通常是以下之一，而报错原文完全看不出是哪一种，用户也不知道该怎么办：
 *   1. 用 App 的内置浏览器打开（微信 / QQ / 钉钉 / 微博…），WebView 屏蔽了 WebRTC；
 *   2. 页面不是安全上下文（用 http:// 打开，或证书没被信任）——
 *      浏览器在非安全上下文里直接不暴露 RTCPeerConnection；
 *   3. 浏览器太老，不支持 RTCRtpTransceiver。
 *
 * 所以这里把原因判准，并给出**可操作**的中文提示。
 */

export type WebRtcProblem = 'ok' | 'insecure-context' | 'no-peer-connection' | 'no-transceiver' | 'in-app-browser';

export interface WebRtcCheck {
  ok: boolean;
  problem: WebRtcProblem;
  /** 给用户看的中文说明 */
  message: string;
  /** 排查用的环境摘要（可截图给管理员） */
  detail: string;
}

const IN_APP_PATTERNS: Array<[RegExp, string]> = [
  [/MicroMessenger/i, '微信'],
  [/QQ\//i, 'QQ'],
  [/QQBrowser/i, 'QQ 浏览器'],
  [/DingTalk/i, '钉钉'],
  [/Weibo/i, '微博'],
  [/Alipay/i, '支付宝'],
  [/BytedanceWebview|aweme/i, '抖音'],
  [/baiduboxapp/i, '百度'],
  [/XiaoMi|MiuiBrowser/i, '小米浏览器'],
  [/HuaweiBrowser|HBPC/i, '华为浏览器'],
  [/Quark/i, '夸克'],
  [/UCBrowser|UCWEB/i, 'UC 浏览器'],
];

/** 当前是不是 App 的内置浏览器（这些 WebView 常把 WebRTC 关掉）。 */
export function detectInAppBrowser(ua = navigator.userAgent): string | null {
  for (const [pattern, name] of IN_APP_PATTERNS) {
    if (pattern.test(ua)) {
      return name;
    }
  }
  return null;
}

/** 判断本机 WebRTC 是否可用，不可用时说明原因。 */
/**
 * @param uaOverride 仅用于测试：显式传入 UA，避免依赖全局 navigator
 */
export function checkWebRtc(uaOverride?: string): WebRtcCheck {
  const ua = uaOverride ?? navigator.userAgent;
  const inApp = detectInAppBrowser(ua);
  const secure = typeof window !== 'undefined' && window.isSecureContext;
  const hasPc = typeof RTCPeerConnection !== 'undefined';
  const hasTransceiver =
    hasPc &&
    (typeof RTCRtpTransceiver !== 'undefined' ||
      'addTrack' in RTCPeerConnection.prototype);

  const detail = [
    `UA: ${ua}`,
    `安全上下文: ${secure ? '是' : '否'}`,
    `RTCPeerConnection: ${hasPc ? '有' : '无'}`,
    `RTCRtpTransceiver/addTrack: ${hasTransceiver ? '有' : '无'}`,
    `内置浏览器: ${inApp ?? '否'}`,
    `地址: ${typeof location !== 'undefined' ? location.origin : '(未知)'}`,
  ].join('\n');

  // 1) 非安全上下文：浏览器压根不暴露 WebRTC
  if (!secure) {
    return {
      ok: false,
      problem: 'insecure-context',
      message:
        '当前页面不是安全连接（HTTPS），浏览器因此禁用了麦克风与语音通话。' +
        '请确认地址栏是 https:// 开头；如果提示证书不受信任，需要先安装本站证书。',
      detail,
    };
  }

  // 2) 完全没有 RTCPeerConnection：多数是 App 内置浏览器屏蔽了
  if (!hasPc) {
    return {
      ok: false,
      problem: inApp ? 'in-app-browser' : 'no-peer-connection',
      message: inApp
        ? `你正在「${inApp}」内置浏览器里打开，它屏蔽了语音通话功能。` +
          '请点右上角菜单选择「在浏览器打开」（或用 Chrome / Safari 打开本链接）。'
        : '这个浏览器没有提供语音通话能力（WebRTC）。请改用 Chrome、Edge 或 Safari 打开。',
      detail,
    };
  }

  // 3) 有 PeerConnection 但缺收发器能力
  if (!hasTransceiver) {
    return {
      ok: false,
      problem: 'no-transceiver',
      message:
        '这个浏览器版本太旧，不支持语音通话所需的接口。请升级浏览器，或改用 Chrome / Edge / Safari。',
      detail,
    };
  }

  return { ok: true, problem: 'ok', message: '', detail };
}
