/**
 * 手机版适配验证。
 *
 * 冒烟测试用的是桌面 UA，走不到手机分支。这里单独用**手机 UA + 窄视口**加载真实产物，
 * 验证：
 *   1. 服务端 /api/client-info 能识别出手机
 *   2. 界面切到手机版（出现手机顶部栏 + 横向菜单，侧栏隐藏）
 *   3. 服务端返回的设备信息被前端采信
 *
 * 用法：node verify-mobile.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { JSDOM } from 'jsdom';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const BACKEND = process.env.GMSR_BACKEND || 'https://<YOUR_SERVER_IP>:4433';
const distDir = path.resolve('dist');

const IPHONE_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

console.log('=== 1) 服务端设备识别（iPhone UA）');
const info = await fetch(BACKEND + '/api/client-info', { headers: { 'User-Agent': IPHONE_UA } }).then((r) => r.json());
console.log('  device=' + info.device + ' isMobile=' + info.isMobile + ' touch=' + info.touch);
if (info.device !== 'mobile') {
  console.error('  ✗ 服务端未识别为手机');
  process.exit(1);
}
console.log('  ✓ 识别正确');

console.log('\n=== 2) 用手机 UA + 窄视口加载前端');
const html = fs.readFileSync(path.join(distDir, 'index.html'), 'utf8');
const dom = new JSDOM(html, {
  url: 'http://127.0.0.1:8080/#/runs',
  pretendToBeVisual: true,
  userAgent: IPHONE_UA,
});
const { window } = dom;
// 窄视口：模拟手机
Object.defineProperty(window, 'innerWidth', { value: 390, writable: true, configurable: true });
Object.defineProperty(window, 'innerHeight', { value: 844, writable: true, configurable: true });
// 触屏
Object.defineProperty(window.navigator, 'maxTouchPoints', { value: 5, configurable: true });
window.__GMSR_SKIP_VOICE__ = true;

globalThis.window = window;
globalThis.document = window.document;
Object.defineProperty(globalThis, 'navigator', { value: window.navigator, configurable: true });
for (const key of ['Node', 'Element', 'HTMLElement', 'SVGElement', 'MutationObserver', 'CustomEvent', 'Event']) {
  globalThis[key] = window[key];
}
globalThis.localStorage = window.localStorage;
globalThis.location = window.location;
globalThis.history = window.history;
globalThis.requestAnimationFrame = (cb) => setTimeout(() => cb(Date.now()), 0);
globalThis.cancelAnimationFrame = clearTimeout;
globalThis.getComputedStyle = window.getComputedStyle.bind(window);
globalThis.matchMedia = () => ({
  matches: false,
  addListener() {},
  removeListener() {},
  addEventListener() {},
  removeEventListener() {},
});
globalThis.atob = (s) => Buffer.from(s, 'base64').toString('binary');
globalThis.btoa = (s) => Buffer.from(s, 'binary').toString('base64');

const nodeFetch = globalThis.fetch;
globalThis.fetch = async (input, init) => {
  let url = typeof input === 'string' ? input : input.url;
  if (url.startsWith('/api')) {
    url = BACKEND + url;
  }
  return nodeFetch(url, init);
};
window.fetch = globalThis.fetch;

const bundle = fs.readdirSync(path.join(distDir, 'assets')).find((f) => f.startsWith('index-') && f.endsWith('.js'));
await import(path.join(distDir, 'assets', bundle) + `?t=${Date.now()}`);

const root = window.document.getElementById('root');
const deadline = Date.now() + 15000;
while (Date.now() < deadline) {
  await new Promise((r) => setTimeout(r, 200));
  if ((root?.textContent || '').includes('车次一览表')) {
    break;
  }
}
await new Promise((r) => setTimeout(r, 800));

const shell = root.querySelector('.app-shell');
const shellClass = shell ? shell.className : '(未找到 .app-shell)';
console.log('  app-shell 的 class: ' + shellClass);

const mobileTop = root.querySelector('.mobile-top');
const sidebar = root.querySelector('.sidebar');
const mobileNav = root.querySelector('.mobile-nav');

const checks = [
  ['app-shell 带 is-mobile', shellClass.includes('is-mobile')],
  ['存在手机顶部栏', Boolean(mobileTop)],
  ['存在横向菜单', Boolean(mobileNav)],
  ['侧栏仍渲染（由 CSS 决定隐藏）', Boolean(sidebar)],
  ['顶栏里有「电脑版」切换按钮', (mobileTop?.textContent || '').includes('电脑版')],
  // 车次一览表：手机上应渲染卡片列表，而不是表格
  ['车次一览表渲染了卡片列表', root.querySelectorAll('.run-card-mobile').length > 0],
  ['手机版不再渲染表格', root.querySelectorAll('table').length === 0],
  ['筛选栏吸顶生效', Boolean(root.querySelector('.toolbar-sticky'))],
];

console.log('\n=== 3) 手机版界面检查');
let ok = true;
for (const [name, pass] of checks) {
  console.log('  ' + (pass ? '✓' : '✗') + ' ' + name);
  if (!pass) {
    ok = false;
  }
}

console.log('\n' + (ok ? 'MOBILE_VERIFY_PASS' : 'MOBILE_VERIFY_FAIL'));
process.exitCode = ok ? 0 : 1;
