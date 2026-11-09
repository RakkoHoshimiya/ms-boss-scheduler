/**
 * 音频增益逻辑的单元测试。
 *
 * 为什么要自动化测：音量调节是「听感」功能，人在电脑前反复试很慢，
 * 而且之前失败 5 轮都因为无法稳定复现。这里用 mock 的 AudioContext
 * 把「增益值算得对不对」变成可断言的逻辑，先把这一层焊死，
 * 剩下的听感确认才交给真人。
 *
 * 重点保障的行为：
 *   1. 增益值随音量设置正确变化（含 >1 的放大）
 *   2. 超出上限会被限制，非法值回退到 1
 *   3. **建图失败时返回 null**（调用方据此保持元素正常播放）→ 绝不静音
 *   4. 麦克风处理器能产出 processedTrack 并正确设置增益
 *
 * 用法：node --experimental-strip-types verify-audio-gain.mjs
 */
import assert from 'node:assert/strict';

// ---------- mock WebAudio ----------
function makeMockAudioContext() {
  const created = { gains: [], sources: 0 };
  class MockGain {
    constructor() {
      this.gain = { value: 1, setTargetAtTime: (v) => { this.gain.value = v; } };
      created.gains.push(this);
    }
    connect() {}
    disconnect() {}
  }
  class MockSource {
    constructor(stream) {
      this.stream = stream;
      created.sources++;
    }
    connect() {}
    disconnect() {}
  }
  class MockAnalyser {
    constructor() {
      this.fftSize = 256;
      // 默认为「静音」：全部采样在中点 128
      this.__samples = new Uint8Array(this.fftSize).fill(128);
      created.analysers = created.analysers || [];
      created.analysers.push(this);
    }
    connect() {}
    disconnect() {}
    getByteTimeDomainData(arr) {
      arr.set(this.__samples.subarray(0, arr.length));
    }
    // 测试辅助：模拟有声音
    __setAudio(amplitude) {
      for (let i = 0; i < this.__samples.length; i++) {
        this.__samples[i] = 128 + (i % 2 === 0 ? amplitude : -amplitude);
      }
    }
  }
  class MockDest {
    constructor() {
      this.stream = {
        getAudioTracks: () => [{ id: 'mock-mic-out', kind: 'audio' }],
      };
    }
  }
  const ctx = {
    currentTime: 0,
    state: 'running',
    createGain: () => new MockGain(),
    createMediaStreamSource: (s) => new MockSource(s),
    createMediaStreamDestination: () => new MockDest(),
    createAnalyser: () => new MockAnalyser(),
    resume: async () => {},
  };
  ctx.__created = created;
  return ctx;
}

// 让模块能拿到 AudioContext（模块通过 window 取）
globalThis.window = { AudioContext: function () { return makeMockAudioContext(); } };
// Node 里没有 MediaStream，这里补一个最小实现（只为能构造 source）
globalThis.MediaStream = class {
  constructor(tracks) {
    this.tracks = tracks || [];
  }
  getAudioTracks() {
    return this.tracks;
  }
};

const mod = await import('./src/utils/audio-gain.ts');
const {
  decideAudioFollow,
  micButtonLabel,
  micButtonTitle,
  deafenButtonLabel,
  deafenButtonTitle,
  createRemoteGraph,
  createMicGainProcessor,
  clampVolume,
  MAX_REMOTE_VOLUME,
  MAX_MIC_VOLUME,
} = mod;

let passed = 0;
let failed = 0;
function check(name, fn) {
  try {
    fn();
    console.log('  ✓ ' + name);
    passed++;
  } catch (e) {
    console.log('  ✗ ' + name + '\n      ' + e.message);
    failed++;
  }
}

console.log('=== 1. 音量范围限制 ===');
check('负值 → 0', () => assert.equal(clampVolume(-3, 1.5), 0));
check('超过上限 → 上限', () => assert.equal(clampVolume(9, 1.5), 1.5));
check('NaN → 1（回退到原始音量，而不是静音）', () => assert.equal(clampVolume(NaN, 1.5), 1));
check('正常值原样保留', () => assert.equal(clampVolume(1.2, 1.5), 1.2));

console.log('\n=== 2. 远端成员增益图 ===');
check('建立成功并返回图对象', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  assert.ok(g, '应返回音频图');
  assert.equal(g.identity, 'u4');
  assert.equal(g.getVolume(), 1, '初始音量应为 1');
});

check('设置 1.5 倍时增益真的变成 1.5（放大可用）', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  g.setVolume(1.5);
  assert.equal(g.getVolume(), 1.5);
  assert.equal(g.gain.gain.value, 1.5, 'GainNode 的实际增益应为 1.5');
});

check('设置 0 时增益为 0（静音某个人）', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  g.setVolume(0);
  assert.equal(g.gain.gain.value, 0);
});

check('超过上限被截断为 1.5', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  g.setVolume(99);
  assert.equal(g.gain.gain.value, MAX_REMOTE_VOLUME);
});

console.log('\n=== 3. 探测增益图是否真的在出声（决定能否安全静音元素） ===');
check('静音时 isProducingAudio 返回 false', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  assert.equal(g.isProducingAudio(), false, '没有声音时应返回 false，元素保持播放');
});
check('有声音时 isProducingAudio 返回 true', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  ctx.__created.analysers[0].__setAudio(40);
  assert.equal(g.isProducingAudio(), true, '有声音时应返回 true，此后才可静音元素');
});
check('analyser 异常时返回 false（保守，宁可让元素出声）', () => {
  const ctx = makeMockAudioContext();
  const g = createRemoteGraph('u4', {}, ctx, {});
  g.analyser.getByteTimeDomainData = () => { throw new Error('boom'); };
  assert.equal(g.isProducingAudio(), false);
});

console.log('\n=== 4. 安全兜底：建图失败不能导致静音 ===');
check('createMediaStreamSource 抛错时返回 null', () => {
  const ctx = makeMockAudioContext();
  ctx.createMediaStreamSource = () => { throw new Error('浏览器不支持'); };
  const g = createRemoteGraph('u4', {}, ctx, {});
  assert.equal(g, null, '失败时必须返回 null，调用方据此保持元素正常播放');
});

check('createGain 抛错时也返回 null', () => {
  const ctx = makeMockAudioContext();
  ctx.createGain = () => { throw new Error('资源耗尽'); };
  const g = createRemoteGraph('u4', {}, ctx, {});
  assert.equal(g, null);
});

console.log('\n=== 5. 音频跟随决策（决定元素该不该静音） ===');
const S = (over = {}) => ({ startedAt: 0, sawAudio: false, silentStreak: 0, ...over });

check('★ 用户把队友调到 0% → 元素必须静音（关键回归）', () => {
  // 对方没说话（audible=false）时也必须静音，否则「调 0 还能听到他」
  const r = decideAudioFollow({ state: S({ sawAudio: true }), now: 99999, audible: false, wantedVolume: 0 });
  assert.equal(r.element, 'muted');
});

check('★ 调到 0% 且从未听到过声音 → 仍然静音', () => {
  const r = decideAudioFollow({ state: S(), now: 99999, audible: false, wantedVolume: 0 });
  assert.equal(r.element, 'muted');
});

check('检测到音频 → 保持静音（声音只走增益节点）', () => {
  const r = decideAudioFollow({ state: S(), now: 100, audible: true, wantedVolume: 1 });
  assert.equal(r.element, 'muted');
  assert.equal(r.next.sawAudio, true);
});

check('宽限期内没声音 → 保持静音（对方只是还没说话）', () => {
  const r = decideAudioFollow({ state: S(), now: 1000, audible: false, wantedVolume: 1, graceMs: 2500 });
  assert.equal(r.element, 'muted');
});

check('★ 宽限期结束仍从未听到 → 恢复元素出声（避免完全没声音）', () => {
  const r = decideAudioFollow({ state: S(), now: 3000, audible: false, wantedVolume: 1, graceMs: 2500 });
  assert.equal(r.element, 'audible');
});

check('曾经听到过、短暂静默 → 仍保持静音（不抖动）', () => {
  const r = decideAudioFollow({ state: S({ sawAudio: true }), now: 5000, audible: false, wantedVolume: 1 });
  assert.equal(r.element, 'muted');
  assert.equal(r.next.silentStreak, 1);
});

check('★ 曾经听到过、持续静默超过阈值 → 恢复元素（应对链路失效）', () => {
  const r = decideAudioFollow({
    state: S({ sawAudio: true, silentStreak: 4 }), now: 9000, audible: false, wantedVolume: 1,
  });
  assert.equal(r.element, 'audible');
  assert.equal(r.next.silentStreak, 0);
});

console.log('\n=== 6. 麦克风增益处理器 ===');
await (async () => {
  const micCheck = (name, fn) => {
    try {
      fn();
      console.log('  ✓ ' + name);
      passed++;
    } catch (e) {
      console.log('  ✗ ' + name + '\n      ' + e.message);
      failed++;
    }
  };

  const p = createMicGainProcessor();
  micCheck('初始音量为 1', () => assert.equal(p.getVolume(), 1));

  await p.init({ audioContext: makeMockAudioContext(), track: { kind: 'audio' } });
  micCheck('init 后产出 processedTrack（可交给 LiveKit 发布）', () => {
    assert.ok(p.processedTrack, '必须有 processedTrack');
    assert.equal(p.processedTrack.kind, 'audio');
  });

  p.setVolume(2);
  micCheck('麦克风可放大到 2 倍', () => assert.equal(p.getVolume(), MAX_MIC_VOLUME));

  p.setVolume(0.5);
  micCheck('麦克风可调小到 0.5', () => assert.equal(p.getVolume(), 0.5));

  p.setVolume(99);
  micCheck('超过上限被截断', () => assert.equal(p.getVolume(), MAX_MIC_VOLUME));

  await p.destroy();
  micCheck('destroy 后清空 processedTrack', () => assert.equal(p.processedTrack, undefined));
})();

console.log('\n=== 7. 语音按钮文案（状态必须与显示一致） ===');
check('★ 麦克风未闭麦 → 显示「开麦中」', () => assert.equal(micButtonLabel(false), '开麦中'));
check('★ 麦克风已闭麦 → 显示「闭麦中」', () => assert.equal(micButtonLabel(true), '闭麦中'));
check('开麦中的提示说明点击会闭麦', () => assert.ok(micButtonTitle(false).includes('点一下闭麦')));
check('闭麦中的提示说明点击会开麦', () => assert.ok(micButtonTitle(true).includes('点一下开麦')));
check('★ 未静音 → 显示「收音中」（表示能听到队友）', () => assert.equal(deafenButtonLabel(false), '收音中'));
check('★ 已静音 → 显示「已静音」', () => assert.equal(deafenButtonLabel(true), '已静音'));
check('收音中的提示说明点击会静音所有队友', () => assert.ok(deafenButtonTitle(false).includes('静音所有队友')));
check('已静音的提示说明点击会恢复收音', () => assert.ok(deafenButtonTitle(true).includes('恢复收音')));

console.log('\n' + '='.repeat(44));
console.log(failed === 0
  ? `AUDIO_GAIN_PASS（${passed} 项全部通过）`
  : `AUDIO_GAIN_FAIL（${failed} 项失败）`);
process.exitCode = failed === 0 ? 0 : 1;
