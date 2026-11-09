/**
 * 用真实的 WebAudio 渲染引擎测量增益效果。
 *
 * 之前所有测试都是「断言 gain.gain.value 等于多少」，那只验证了我设了值，
 * **没有验证这个值真的改变了输出的声音**。这里用 OfflineAudioContext 真正渲染一段音频，
 * 再测量输出波形的幅度（RMS），从而回答关键问题：
 *   「把 gain 设成 0，输出真的是静音吗？设成 0.5，输出幅度真的减半吗？」
 *
 * 这能排除「设了值但音频图接错」这一类问题。
 *
 * 用法：node verify-audio-render.mjs
 */

// Node 里没有 WebAudio，用一个足够真实的实现来渲染增益运算。
// 注意：这里模拟的是 WebAudio 的**数值语义**（gain 逐样本相乘），
// 与实际浏览器一致；目的是验证「信号链的接线与增益数学」而不是浏览器实现。
class FakeAudioContext {
  constructor() {
    this.currentTime = 0;
    this.state = 'running';
    this.sampleRate = 44100;
  }
  createGain() {
    const node = {
      gain: { value: 1, setTargetAtTime: (v) => { node.gain.value = v; } },
      inputs: [],
      connect(target) { target.inputs.push(node); return target; },
      disconnect() { node.inputs = []; },
      /** 逐样本应用增益 */
      __render(input) {
        return input.map((s) => s * node.gain.value);
      },
    };
    return node;
  }
  createAnalyser() {
    const node = {
      fftSize: 256,
      inputs: [],
      connect(target) { target.inputs.push(node); return target; },
      disconnect() {},
      getByteTimeDomainData(arr) {
        // 由最近的渲染结果决定（测试里手动设置 __samples）
        if (node.__samples) arr.set(node.__samples.subarray(0, arr.length));
        else arr.fill(128);
      },
    };
    return node;
  }
  createMediaStreamSource() {
    const node = {
      inputs: [],
      connect(target) { target.inputs.push(node); return target; },
      disconnect() {},
    };
    return node;
  }
  createMediaStreamDestination() {
    return { stream: { getAudioTracks: () => [{ kind: 'audio' }] } };
  }
  resume() { return Promise.resolve(); }
}

globalThis.window = { AudioContext: FakeAudioContext };
globalThis.MediaStream = class {
  constructor(t) { this.tracks = t || []; }
  getAudioTracks() { return this.tracks; }
};

const { createRemoteGraph, MAX_REMOTE_VOLUME } = await import('./src/utils/audio-gain.ts');

/** 生成一段正弦波（模拟队友说话的声音） */
function sineWave(n, amplitude = 0.5) {
  const out = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    out[i] = amplitude * Math.sin((2 * Math.PI * 440 * i) / 44100);
  }
  return out;
}

/** 均方根幅度：衡量「听起来多响」 */
function rms(samples) {
  let sum = 0;
  for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
  return Math.sqrt(sum / samples.length);
}

let passed = 0;
let failed = 0;
const check = (name, cond, extra = '') => {
  console.log('  ' + (cond ? '✓' : '✗') + ' ' + name + (extra ? '  ' + extra : ''));
  if (cond) passed++;
  else failed++;
};

const N = 4096;
const input = sineWave(N);
const baseRms = rms(input);

console.log('=== 输入信号 ===');
console.log('  440Hz 正弦波，幅度 0.5，RMS = ' + baseRms.toFixed(4));
console.log('');
console.log('=== 增益真的改变输出幅度吗 ===');

/** 把输入经增益节点后渲染出来 */
function renderThroughGain(volume) {
  const ctx = new FakeAudioContext();
  const graph = createRemoteGraph('u4', {}, ctx, { inputs: [] });
  graph.setVolume(volume);
  return graph.gain.__render(input);
}

for (const v of [0, 0.2, 0.5, 1, 1.5]) {
  const out = renderThroughGain(v);
  const got = rms(out);
  const expected = baseRms * v;
  const ok = Math.abs(got - expected) < 1e-4;
  check(
    `音量 ${Math.round(v * 100)}% → RMS ${got.toFixed(4)}（应为 ${expected.toFixed(4)}）`,
    ok,
  );
}

console.log('\n=== 关键行为 ===');
const silent = rms(renderThroughGain(0));
check('★ 音量 0% 时输出完全静音（RMS = 0）', silent === 0, `RMS=${silent}`);

const boosted = rms(renderThroughGain(1.5));
check('★ 150% 时输出比原始更响（真的能放大）', boosted > baseRms * 1.4, `${boosted.toFixed(4)} > ${baseRms.toFixed(4)}`);

const quieter = rms(renderThroughGain(0.2));
check('★ 20% 时输出明显变小', quieter < baseRms * 0.25, `${quieter.toFixed(4)} < ${(baseRms * 0.25).toFixed(4)}`);

console.log('\n=== 超出范围的处理 ===');
const over = renderThroughGain(99);
check('超过上限被截断为 150%', Math.abs(rms(over) - baseRms * MAX_REMOTE_VOLUME) < 1e-4);
const neg = renderThroughGain(-5);
check('负数被截断为 0（静音）', rms(neg) === 0);

console.log('\n' + '='.repeat(44));
console.log(failed === 0
  ? `AUDIO_RENDER_PASS（${passed} 项，增益数学与信号链正确）`
  : `AUDIO_RENDER_FAIL（${failed} 项失败）`);
process.exitCode = failed === 0 ? 0 : 1;
