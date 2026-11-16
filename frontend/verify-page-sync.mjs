import { JSDOM } from 'jsdom';
import fs from 'node:fs';
import assert from 'node:assert/strict';

const dom = new JSDOM('<div id="root"></div>', { url: 'http://localhost/#/runs', pretendToBeVisual: true });
const w = dom.window; w.__GMSR_SKIP_VOICE__ = true;
for (const k of ['window', 'document', 'localStorage', 'location', 'history', 'Node', 'Element', 'HTMLElement', 'MutationObserver', 'Event']) globalThis[k] = k === 'window' ? w : w[k];
Object.defineProperty(globalThis, 'navigator', { value: w.navigator, configurable: true });
globalThis.getComputedStyle = w.getComputedStyle.bind(w);
globalThis.requestAnimationFrame = cb => setTimeout(cb, 0); globalThis.cancelAnimationFrame = clearTimeout;
globalThis.matchMedia = () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} });
globalThis.confirm = () => true; localStorage.setItem('gmsr.token', 'test-session');

const boss = { id: 1, groupId: 1, groupName: '测试分组', name: '测试BOSS', tier: 'N', tierLabel: '普通', tiers: ['N'], allowedTiers: ['N'], capacity: 6, sortOrder: 0, enabled: true, runCount: 1, assignedCount: 1 };
const member = { id: 1, characterId: 1, playerId: 7, playerNickname: '测试队友', job: 'Kanna', power: 100, slotNo: 1, role: 'MEMBER', status: 'CONFIRMED', mainChar: true };
let run = { id: 1, bossId: 1, bossName: boss.name, groupId: 1, groupName: boss.groupName, tier: 'N', tierLabel: '普通', capacity: 6, runIndex: 1, scheduled: '原始时间', status: 'PLANNED', memberCount: 1, powerTotal: 100, members: [member] };
const otherBoss={...boss,id:2,name:'另一个BOSS'};
const otherRun={...run,id:2,bossId:2,bossName:otherBoss.name,scheduled:'BOSS-B-only',members:[],memberCount:0};
let runRequests = 0, delay = false, release;
globalThis.fetch = async url => {
  let body = [];
  if (url === '/api/auth/me') body = { id: 7, username: 'test', displayName: '测试用户', role: 'VIEWER', canSchedule: true, playerId: 7 };
  else if (url === '/api/runs') {
    runRequests++; body = [structuredClone(run),otherRun];
    if (delay) { delay = false; await new Promise(resolve => { release = resolve; }); }
  } else if (url === '/api/bosses') body = [boss,otherBoss];
  else if (url === '/api/boss-groups') body = [{ id: 1, name: boss.groupName, sortOrder: 0, bossCount: 1 }];
  else if (url === '/api/players') body = [{ id: 7, nickname: member.playerNickname, active: true, sortOrder: 0, characterCount: 1, characters: [{ ...member, id: 1, sortOrder: 0 }] }];
  else if (url === '/api/players/7/runs') body = [{ ...run, runId: 1, characterId: 1, characterJob: 'Kanna', characterPower: 100 }];
  else if (url === '/api/status-reset') body = { lastResetAt: '2026-11-12T00:00:00Z', nextResetAt: '2026-11-19T00:00:00Z' };
  else if (url === '/api/client-info') body = { mobile: false };
  return new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } });
};
w.fetch = globalThis.fetch;
const assets = new URL('./dist/assets/', import.meta.url);
await import(new URL(fs.readdirSync(assets).find(x => /^index-.*\.js$/.test(x)), assets));
const wait = (ms = 160) => new Promise(resolve => setTimeout(resolve, ms));
const button = label => [...document.querySelectorAll('button')].find(el => el.textContent.trim() === label);
const route = async hash => { w.location.hash = hash; await wait(); await wait(); };
const type = (el, value) => { Object.getOwnPropertyDescriptor(w.HTMLInputElement.prototype, 'value').set.call(el, value); el.dispatchEvent(new w.Event('input', { bubbles: true })); };
await wait(); await wait();
assert(document.body.textContent.includes('原始时间'));
run.scheduled = '切回后最新时间'; const before = runRequests;
w.dispatchEvent(new w.Event('focus')); document.dispatchEvent(new w.Event('visibilitychange'));
await wait(); assert(document.body.textContent.includes('切回后最新时间')); assert.equal(runRequests, before + 1, 'Focus and visibility events should coalesce');

await route('#/bosses'); document.querySelector('.boss-card').click(); await wait(); await wait();
button('设置').click(); await wait();
type(document.querySelector('#run-form input[type="text"]'), '我的未保存时间'); await wait();
run.scheduled = '其他管理员已更新'; const protectedRequests = runRequests;
w.dispatchEvent(new w.Event('focus')); await wait();
assert.equal(runRequests, protectedRequests); assert.equal(document.querySelector('#run-form input[type="text"]').value, '我的未保存时间');
assert(document.body.textContent.includes('已暂缓自动同步'));
button('取消').click(); await wait(); await wait(); assert(document.body.textContent.includes('其他管理员已更新'));

// A response started before editing must also be discarded rather than applied late.
await wait(1100); run.scheduled = '过期请求中的时间'; delay = true;
w.dispatchEvent(new w.Event('focus')); await wait(); assert(release);
button('设置').click(); await wait(); type(document.querySelector('#run-form input[type="text"]'), '请求期间的新输入'); await wait();
run.scheduled = '最终最新时间'; release(); await wait();
assert.equal(document.querySelector('#run-form input[type="text"]').value, '请求期间的新输入');
assert(!document.body.textContent.includes('过期请求中的时间'));
button('取消').click(); await wait(); await wait(); assert(document.body.textContent.includes('最终最新时间'));

Object.defineProperty(document, 'visibilityState', { value: 'hidden', configurable: true }); const hiddenRequests = runRequests;
w.dispatchEvent(new w.Event('focus')); await wait(); assert.equal(runRequests, hiddenRequests);
await route('#/'); Object.defineProperty(document, 'visibilityState', { value: 'visible', configurable: true });
run.scheduled = '我的车次也同步'; document.dispatchEvent(new w.Event('visibilitychange')); await wait(); await wait();
assert(document.body.textContent.includes('我的车次也同步'));
// A delayed ordinary BOSS load (outside usePageSync) must never overwrite a new selection.
await route('#/bosses');delay=true;document.querySelector('.boss-card').click();await wait();const releaseBossA=release;
button('← 返回 BOSS 列表').click();await wait();
[...document.querySelectorAll('.boss-card')].find(el=>el.textContent.includes(otherBoss.name)).click();await wait();await wait();
assert(document.body.textContent.includes('BOSS-B-only'));
releaseBossA();await wait();assert(document.body.textContent.includes('BOSS-B-only'));
assert(!document.body.textContent.includes('我的车次也同步'),'The old BOSS response cannot display actionable runs under the new BOSS title');
// Combo selections use virtual negative IDs; syncing must resolve their shared key.
boss.comboKey='test-combo';boss.comboName='测试融合车';boss.comboTier='N';
otherBoss.comboKey='test-combo';otherBoss.comboName='测试融合车';otherBoss.comboTier='N';
await route('#/changelog');await route('#/bosses');document.querySelector('.boss-card').click();await wait();await wait();
assert(document.querySelector('h1').textContent.includes('测试融合车'));w.dispatchEvent(new w.Event('focus'));await wait();await wait();
assert(document.querySelector('h1').textContent.includes('测试融合车'),'Syncing must keep the selected virtual combo detail open');
console.log('PAGE_SYNC_PASS: focus/visibility sync, event coalescing, hidden pause, unsaved input protection, late-response protection, my-runs refresh');
console.log('BOSS_SELECTION_PASS: delayed old loads cannot overwrite a newly selected BOSS');
dom.window.close(); process.exit(0);
