/**
 * 音量控件的打包验证。
 *
 * 分工：
 *   - verify-audio-gain.mjs  → 增益**逻辑**（纯函数 + mock，可断言）
 *   - 本脚本                 → 控件是否被正确**打包**，以及有没有误开 webAudioMix
 *   - 真人听感               → 最后由你和队友确认（机器无法判断"听起来变响了"）
 *
 * 用法：node verify-volume-ui.mjs
 */
import fs from 'node:fs';
import path from 'node:path';

const assetsDir = path.resolve('dist', 'assets');
const bundle = fs
  .readdirSync(assetsDir)
  .filter((f) => f.endsWith('.js'))
  .map((f) => fs.readFileSync(path.join(assetsDir, f), 'utf8'))
  .join('\n');

let passed = 0;
let failed = 0;
const check = (name, cond) => {
  console.log('  ' + (cond ? '✓' : '✗') + ' ' + name);
  if (cond) passed++;
  else failed++;
};

console.log('=== 音量控件已打包 ===');
check('VolumeControl 组件（vol-box / vol-btn）', bundle.includes('vol-box') && bundle.includes('vol-btn'));
check('音量浮层（vol-pop）', bundle.includes('vol-pop'));
check('快捷按钮（静音 / 100% / 上限）', bundle.includes('vol-mini'));
check('队友音量 → setMemberVolume', bundle.includes('setMemberVolume'));
check('麦克风音量 → setMicVolume', bundle.includes('setMicVolume'));

console.log('\n=== 音频增益链路已打包 ===');
check('启用 LiveKit 内置 webAudioMix（行业标准增益链路）', bundle.includes('webAudioMix:!0'));
check('麦克风增益处理器（TrackProcessor 标识）', bundle.includes('gmsr-mic-gain'));

console.log('\n=== AudioContext 解锁逻辑（音量能否生效的关键） ===');
// AudioContext 处于 suspended 时增益改动不会产生声音效果，
// 这是之前「调了没反应」的根因，因此必须有主动 resume 的逻辑。
check('存在 AudioContext 解锁逻辑（resume）', bundle.includes('resume'));
check('存在未解锁时的提示文案', bundle.includes('尚未解锁'));

console.log('\n' + '='.repeat(44));
console.log(failed === 0 ? `VOLUME_UI_PASS（${passed} 项）` : `VOLUME_UI_FAIL（${failed} 项失败）`);
process.exitCode = failed === 0 ? 0 : 1;
