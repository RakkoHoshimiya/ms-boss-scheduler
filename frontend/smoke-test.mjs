// 前端构建产物冒烟测试：用 jsdom 加载真实打包 JS，验证首页/车次表/我的车次/管理员页能渲染、API 通、无运行时错误。
// 用法：node smoke-test.mjs [后端地址]
import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import path from 'node:path';

// 生产环境 8080(HTTP) 会 301 跳到 4433(HTTPS)，所以默认走 HTTPS；
// 服务器用的是私有 CA 签发的证书，本地跑测试需要放宽 TLS 校验（仅影响本进程）。
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const BACKEND = process.argv[2] ?? 'https://<YOUR_SERVER_IP>:4433';
const distDir = path.resolve('dist');
const ADMIN = { username: 'admin', password: 'CHANGE_ME_ADMIN_PASSWORD' };
// 专用冒烟测试账号（管理员建的，绑定玩家「群友C」）。
// 不要用真人账号：群友会自己改密码，测试会莫名其妙失败。
const PLAYER = { username: 'smoke', password: process.env.GMSR_SMOKE_PASSWORD ?? 'CHANGE_ME_SMOKE_PASSWORD' };

async function login(cred) {
  const response = await fetch(`${BACKEND}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cred),
  });
  if (!response.ok) {
    throw new Error(`登录失败 ${response.status}`);
  }
  const data = await response.json();
  // 测试账号是新建的，第一次登录会被要求改密码；这里自动改成固定密码，
  // 之后每次跑测试都用这个密码，保证测试可重复。
  if (data.mustChangePassword) {
    const changed = await fetch(`${BACKEND}/api/auth/password`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'gmsr-token': data.token },
      body: JSON.stringify({ oldPassword: cred.password, newPassword: 'CHANGE_ME_SMOKE_PASSWORD' }),
    });
    if (!changed.ok) {
      throw new Error(`测试账号改密码失败 ${changed.status}`);
    }
    const again = await fetch(`${BACKEND}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: cred.username, password: 'CHANGE_ME_SMOKE_PASSWORD' }),
    });
    if (!again.ok) {
      throw new Error(`测试账号二次登录失败 ${again.status}`);
    }
    const second = await again.json();
    return { token: second.token, refreshToken: second.refreshToken };
  }
  return { token: data.token, refreshToken: data.refreshToken };
}

// 注意：这些必须放在普通对象里，而不是散落的全局变量 —— renderPage 每次都会替换
// globalThis.window，散落的全局声明可能被 jsdom 环境连带重置，导致后续用例认不出账号。
const __sessions = { adminAccess: null, adminRefresh: null, playerAccess: null, playerRefresh: null };

async function renderPage({ name, hash, token, refreshToken, expect, minTiles = 0, clickBoss = null }) {
  // 按用例实际使用的 token 配对这个账号的 refresh token；
  // 访客用例（token 为空）自然不带 refresh token。
  // 如果只给 access token 不带 refresh token，api 层收到 401 时会直接判定掉线，
  // 这与真实「两个都存了」的场景不一致，所以这里必须成对提供。
  const refresh =
    refreshToken ??
    (token && token === __sessions.adminAccess
      ? __sessions.adminRefresh
      : token && token === __sessions.playerAccess
        ? __sessions.playerRefresh
        : null);
  const html = fs.readFileSync(path.join(distDir, 'index.html'), 'utf8');
  // jsdom 无法正确加载多个懒加载分包（会重复 React 实例），跳过语音 Provider
  const dom = new JSDOM(html, { url: `http://127.0.0.1:8080/${hash}`, pretendToBeVisual: true });
  // 必须在加载 bundle 之前设置，main.tsx 会据此跳过懒加载的 VoiceProvider
  dom.window.__GMSR_SKIP_VOICE__ = true;

  const window = dom.window;
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
  if (token) {
    window.localStorage.setItem('gmsr.token', token);
    if (refresh) window.localStorage.setItem('gmsr.refresh', refresh);
  }

  globalThis.__dbg = [];
  const errors = [];
  window.addEventListener('error', (e) => errors.push('window.error: ' + e.message));
  window.onerror = (msg) => errors.push('onerror: ' + msg);
  const origError = console.error;
  console.error = (...args) => {
    errors.push('console.error: ' + args.map(String).join(' '));
    origError(...args);
  };

  const nodeFetch = globalThis.fetch;
  const calls = [];
  globalThis.fetch = async (input, init) => {
    let url = typeof input === 'string' ? input : input.url;
    if (url.startsWith('/api')) {
      url = BACKEND + url;
    }
    calls.push(url);
    return nodeFetch(url, init);
  };
  window.fetch = globalThis.fetch;

  const bundle = fs.readdirSync(path.join(distDir, 'assets')).find((f) => f.startsWith('index-') && f.endsWith('.js'));
  await import(path.join(distDir, 'assets', bundle) + `?t=${Date.now()}${Math.random()}`);

  const root = window.document.getElementById('root');
  const deadline = Date.now() + 15000;
  let text = '';
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 200));
    text = (root?.textContent || '').replace(/\s+/g, ' ').trim();
    if (expect.every((needle) => text.includes(needle)) && root.querySelectorAll('.run-tile').length >= minTiles) {
      break;
    }
  }

  // 可选：点开某个 BOSS，验证二级页面（难度 / 周期 / 排班）
  if (clickBoss) {
    const card = [...root.querySelectorAll('.boss-card')].find((c) => (c.textContent || '').includes(clickBoss));
    if (card) {
      card.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
      const sub = Date.now() + 12000;
      while (Date.now() < sub) {
        await new Promise((r) => setTimeout(r, 200));
        text = (root?.textContent || '').replace(/\s+/g, ' ').trim();
        if (expect.every((needle) => text.includes(needle))) break;
      }
    }
  }

  const tiles = root.querySelectorAll('.run-tile').length;
  const nav = [...root.querySelectorAll('.side-link')].map((a) => a.textContent.replace(/\s+/g, ' ').trim());
  const apiCalls = calls.filter((u) => u.includes('/api/'));
  const missing = expect.filter((needle) => !text.includes(needle));
  const pass = missing.length === 0 && tiles >= minTiles && errors.length === 0;

  console.log(`\n=== [${name}] ${hash || '/'}`);
  console.log('  侧边导航:', nav.join(' | ') || '(无)');
  console.log('  卡片数:', tiles, '| API 调用:', apiCalls.length);
  console.log('  文本片段:', text.slice(0, 220));
  if (missing.length) {
    console.log('  ✗ 缺少内容:', missing);
  }
  if (errors.length) {
    console.log('  ✗ 运行时错误:', errors.slice(0, 3));
  }
  console.log(pass ? `  ✓ ${name} 通过` : `  ✗ ${name} 失败`);
  return pass;
}

const admin = await login(ADMIN);
const player = await login(PLAYER);
const adminToken = admin.token;
const adminRefresh = admin.refreshToken;
const playerToken = player.token;
const playerRefresh = player.refreshToken;
__sessions.adminAccess = adminToken;
__sessions.adminRefresh = adminRefresh;
__sessions.playerAccess = playerToken;
__sessions.playerRefresh = playerRefresh;

const results = [];
results.push(
  await renderPage({
    name: '访客首页（默认车次一览表）',
    hash: '',
    expect: ['车次一览表', '我参加', '总战力', '神选者塞伦'],
  }),
);
results.push(
  await renderPage({
    name: '群友首页（我的车次）',
    hash: '',
    token: playerToken,
    expect: ['我的车次', '我的群友身份'],
    minTiles: 2,
  }),
);

results.push(
  await renderPage({
    name: '车次一览表',
    hash: '#/runs',
    token: playerToken,
    expect: ['车次一览表', '总战力', '神选者塞伦'],
    expectFirstNav: '车次一览表',
  }),
);
results.push(
  await renderPage({
    name: '旧排班管理地址（已并入 boss排班，应重定向）',
    hash: '#/schedule',
    token: adminToken,
    expect: ['boss排班', '神选者塞伦'],
  }),
);

results.push(
  await renderPage({
    name: '群友角色一览表',
    hash: '#/players',
    token: adminToken,
    expect: ['群友角色一览表', '显示名称', '按战力从高到低'],
  }),
);

results.push(
  await renderPage({
    name: 'boss排班·第1层（BOSS 图标 + 名称）',
    hash: '#/bosses',
    token: adminToken,
    expect: ['boss排班', '神选者塞伦', '灿烂的凶星'],
  }),
);

results.push(
  await renderPage({
    name: '我的信息（改名 + 本人维护角色）',
    hash: '#/my-characters',
    token: playerToken,
    expect: ['我的信息', '显示名称', '我的游戏角色', '新增角色', '职业'],
  }),
);
// 注意：Boss语音频道是懒加载分包（livekit-client 体积大），
// jsdom 不支持多 chunk 的动态导入，因此不在冒烟里覆盖；
// 该页面的验证方式是真实浏览器打开 #/voice。
results.push(
  await renderPage({
    name: 'boss排班·第2~4层（难度 → 周期 → 排班）',
    hash: '#/bosses',
    token: adminToken,
    expect: ['选择难度', '选择周期', '排班', '一键复制'],
    clickBoss: '监视者卡尔洛斯',
  }),
);
results.push(
  await renderPage({
    name: '更新记录（未登录也可看）',
    hash: '#/changelog',
    expect: ['更新记录', '融合车', 'SA-Token', '新功能'],
  }),
);

results.push(
  await renderPage({
    name: '管理员·系统设置',
    hash: '#/admin',
    token: adminToken,
    expect: ['系统设置', '账号列表', '密码状态', '重置密码'],
  }),
);

console.log('\n================ 汇总 ================');
console.log(results.map((ok, i) => `${i + 1}. ${ok ? 'PASS' : 'FAIL'}`).join('  '));
const allPass = results.every(Boolean);
console.log(allPass ? 'SMOKE_TEST_PASS' : 'SMOKE_TEST_FAIL');
process.exit(allPass ? 0 : 1);
