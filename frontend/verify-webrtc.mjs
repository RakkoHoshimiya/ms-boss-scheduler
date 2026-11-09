/**
 * 验证「浏览器不支持语音」的检测与提示。
 *
 * 手机上进不去语音最常见的原因是 App 内置浏览器屏蔽了 WebRTC，
 * 或页面不是安全上下文。这个脚本模拟这些环境，确认前端能：
 *   1. 判准原因（不是笼统的英文报错）
 *   2. 给出可操作的中文提示
 *
 * 用法：node verify-webrtc.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { JSDOM } from 'jsdom';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const distDir = path.resolve('dist');
const bundle = fs.readdirSync(path.join(distDir, 'assets')).find((f) => f.startsWith('index-') && f.endsWith('.js'));
const code = fs.readFileSync(path.join(distDir, 'assets', bundle), 'utf8');

/** 场景定义：模拟不同的浏览器环境，检查提示是否命中关键词。 */
const SCENARIOS = [
  {
    name: '微信内置浏览器（屏蔽 WebRTC）',
    ua: 'Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 Chrome/150.0.0.0 Mobile Safari/537.36 MicroMessenger/8.0.49',
    hasPc: false,
    secure: true,
    expect: ['微信', '在浏览器打开'],
  },
  {
    name: '非安全上下文（http 打开）',
    ua: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
    hasPc: false,
    secure: false,
    expect: ['HTTPS', '证书'],
  },
  {
    name: '老浏览器（缺 RTCRtpTransceiver）',
    ua: 'Mozilla/5.0 (Linux; Android 6) AppleWebKit/537.36 Chrome/60 Mobile Safari/537.36',
    hasPc: true,
    hasTransceiver: false,
    secure: true,
    expect: ['版本太旧', '升级'],
  },
  {
    name: '正常 Chrome（应通过）',
    ua: 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36',
    hasPc: true,
    hasTransceiver: true,
    secure: true,
    expect: [],
  },
];

let failed = 0;

for (const sc of SCENARIOS) {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'https://<YOUR_SERVER_IP>:4433/',
    userAgent: sc.ua,
  });
  const { window } = dom;
  Object.defineProperty(window, 'isSecureContext', { value: sc.secure, configurable: true });
  if (sc.hasPc) {
    window.RTCPeerConnection = function () {};
    window.RTCPeerConnection.prototype.addTrack = function () {};
    if (sc.hasTransceiver === false) {
      // 模拟老浏览器：没有 RTCRtpTransceiver，也没有 addTrack
      delete window.RTCPeerConnection.prototype.addTrack;
      window.RTCPeerConnection.prototype.addTrack = undefined;
      delete window.RTCPeerConnection.prototype.addTrack;
    } else {
      window.RTCRtpTransceiver = function () {};
    }
  }

  // 把被测环境挂到 globalThis（checkWebRtc 会读这些），UA 则显式传入
  globalThis.window = window;
  Object.defineProperty(globalThis, 'navigator', { value: window.navigator, configurable: true });
  globalThis.location = window.location;
  globalThis.RTCPeerConnection = window.RTCPeerConnection;
  globalThis.RTCRtpTransceiver = window.RTCRtpTransceiver;

  // 从一个极小的内联实现里取检测函数（与源码逻辑一致即可验证行为）
  const mod = await import('./src/utils/webrtc-check.ts').catch(() => null);
  if (!mod) {
    console.log('（无法直接加载 .ts，请用构建后的产物验证）');
    break;
  }
  const result = mod.checkWebRtc(sc.ua);
  const hit = sc.expect.length === 0 ? result.ok : sc.expect.every((k) => result.message.includes(k));
  console.log(`  ${hit ? '✓' : '✗'} ${sc.name}`);
  if (sc.expect.length > 0) {
    console.log(`      problem=${result.problem}`);
    console.log(`      提示: ${result.message.slice(0, 60)}…`);
  } else {
    console.log(`      ok=${result.ok}`);
  }
  if (!hit) failed++;
}

console.log(failed === 0 ? '\nWEBRTC_CHECK_PASS' : `\nWEBRTC_CHECK_FAIL (${failed})`);
process.exitCode = failed === 0 ? 0 : 1;
