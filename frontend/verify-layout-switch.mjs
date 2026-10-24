/** 验证：手机点了「电脑版」之后，还能不能切回手机版 */
import fs from 'node:fs';
import path from 'node:path';
import { JSDOM } from '/data/dsh/home/京东云1pannel/gmsr/frontend/node_modules/jsdom/lib/api.js';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const FRONTEND = '/data/dsh/home/京东云1pannel/gmsr/frontend';
const distDir = path.join(FRONTEND, 'dist');
const BACKEND = 'https://<YOUR_SERVER_IP>:4433';
const nodeFetch = globalThis.fetch;

const MOBILE_UA = 'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';
const dom = new JSDOM(fs.readFileSync(path.join(distDir, 'index.html'), 'utf8'), {
  url: 'http://127.0.0.1:8080/#/runs', pretendToBeVisual: true, userAgent: MOBILE_UA,
});
const { window } = dom;
window.__GMSR_SKIP_VOICE__ = true;
// 模拟手机视口
Object.defineProperty(window, 'innerWidth', { value: 390, configurable: true, writable: true });
globalThis.window = window; globalThis.document = window.document;
Object.defineProperty(globalThis, 'navigator', { value: window.navigator, configurable: true });
for (const k of ['Node','Element','HTMLElement','SVGElement','MutationObserver','CustomEvent','Event','PointerEvent','MouseEvent']) globalThis[k] = window[k];
globalThis.localStorage = window.localStorage; globalThis.location = window.location; globalThis.history = window.history;
globalThis.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
globalThis.cancelAnimationFrame = clearTimeout;
globalThis.getComputedStyle = window.getComputedStyle.bind(window);
globalThis.matchMedia = () => ({ matches:false, addListener(){}, removeListener(){}, addEventListener(){}, removeEventListener(){} });
globalThis.atob = (s) => Buffer.from(s,'base64').toString('binary');
globalThis.btoa = (s) => Buffer.from(s,'binary').toString('base64');
globalThis.fetch = async (i, init) => { let u = typeof i === 'string' ? i : i.url; if (u.startsWith('/api')) u = BACKEND + u; return nodeFetch(u, init); };
window.fetch = globalThis.fetch;

// 关键：预置「用户已经选了电脑版」
window.localStorage.setItem('gmsr.layout', 'desktop');

const bundle = fs.readdirSync(path.join(distDir, 'assets')).find((f) => f.startsWith('index-') && f.endsWith('.js'));
await import(path.join(distDir, 'assets', bundle) + '?t=' + Date.now());

const root = document.getElementById('root');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
await sleep(3000);

let fail = 0;
const check = (ok, label, extra = '') => { if (!ok) fail++; console.log(`  ${ok ? '✓' : '✗'} ${label}${extra ? '  ' + extra : ''}`); };

const shell = () => root.querySelector('.app-shell');
const fab = () => root.querySelector('.layout-fab');

check(window.localStorage.getItem('gmsr.layout') === 'desktop', '初始偏好 = desktop（模拟用户已选电脑版）');
check(shell()?.classList.contains('is-desktop'), '布局是 is-desktop', shell()?.className || '');
check(!!fab(), '存在「切回手机版」浮动按钮（修复点）');

// 点浮动按钮
fab()?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
await sleep(600);

check(window.localStorage.getItem('gmsr.layout') === null, '点击后本地偏好已清除（回到自动识别）');
check(shell()?.classList.contains('is-mobile'), '布局切回 is-mobile（手机版）', shell()?.className || '');
check(!fab(), '浮动按钮已消失（不再是电脑版）');

// 再验证：手机顶栏的「电脑版」按钮仍在手机版下可用
const topBtn = [...root.querySelectorAll('.mobile-top button')].find((b) => (b.textContent || '').includes('电脑版'));
check(!!topBtn, '手机版顶栏仍有「电脑版」按钮');
topBtn?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
await sleep(500);
check(window.localStorage.getItem('gmsr.layout') === 'desktop', '能再次切到电脑版');
check(!!fab(), '切到电脑版后浮动按钮回来（可逆）');

console.log('\n' + (fail === 0 ? 'LAYOUT_SWITCH_PASS（手机版/电脑版可自由切换）' : `LAYOUT_SWITCH_FAIL（${fail} 项）`));
process.exit(fail === 0 ? 0 : 1);
