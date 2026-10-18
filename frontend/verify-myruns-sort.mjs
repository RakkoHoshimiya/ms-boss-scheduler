/**
 * 「我的车次」排序验证：未完成在前、已完成沉底。
 *
 * 用法：node --experimental-strip-types verify-myruns-sort.mjs
 */
import assert from 'node:assert/strict';

const { sortMyRuns } = await import('./src/features/combo.ts');

let passed = 0;
let failed = 0;
const check = (name, fn) => {
  try {
    fn();
    console.log('  ✓ ' + name);
    passed++;
  } catch (e) {
    console.log('  ✗ ' + name + '\n      ' + e.message);
    failed++;
  }
};

const run = (bossName, runIndex, status) => ({ bossName, runIndex, status });

console.log('=== 未完成优先 ===');
check('已完成的排到最后', () => {
  const input = [
    run('卡琳', 1, 'DONE'),
    run('斯乌', 1, 'PLANNED'),
    run('林波', 2, 'DONE'),
    run('监视者卡尔洛斯', 1, 'PLANNED'),
  ];
  const out = sortMyRuns(input);
  assert.deepEqual(
    out.map((x) => x.bossName + x.runIndex),
    ['斯乌1', '监视者卡尔洛斯1', '卡琳1', '林波2'],
    '未完成应在前面，已完成在后',
  );
});

check('全部未完成时保持按 BOSS 名 + 趟数', () => {
  const out = sortMyRuns([run('B', 2, 'PLANNED'), run('A', 2, 'PLANNED'), run('A', 1, 'PLANNED')]);
  assert.deepEqual(out.map((x) => x.bossName + x.runIndex), ['A1', 'A2', 'B2']);
});

check('全部已完成时同样按 BOSS 名 + 趟数', () => {
  const out = sortMyRuns([run('B', 1, 'DONE'), run('A', 2, 'DONE'), run('A', 1, 'DONE')]);
  assert.deepEqual(out.map((x) => x.bossName + x.runIndex), ['A1', 'A2', 'B1']);
});

console.log('\n=== 混合场景 ===');
check('★ 部分完成后，未完成的全部在前面', () => {
  const input = [
    run('监视者卡尔洛斯', 1, 'DONE'),
    run('监视者卡尔洛斯', 2, 'PLANNED'),
    run('神选者塞伦', 1, 'DONE'),
    run('神选者塞伦', 2, 'PLANNED'),
    run('融合车', 1, 'DONE'),
  ];
  const out = sortMyRuns(input);
  const statuses = out.map((x) => x.status);
  const firstDone = statuses.indexOf('DONE');
  const lastPlanned = statuses.lastIndexOf('PLANNED');
  assert.ok(firstDone > lastPlanned, '所有 PLANNED 必须在所有 DONE 之前');
});

check('不修改原数组（纯函数）', () => {
  const input = [run('B', 1, 'DONE'), run('A', 1, 'PLANNED')];
  const snapshot = JSON.stringify(input);
  sortMyRuns(input);
  assert.equal(JSON.stringify(input), snapshot, '原数组不应被改动');
});

console.log('\n' + '='.repeat(44));
console.log(failed === 0 ? `MYRUNS_SORT_PASS（${passed} 项）` : `MYRUNS_SORT_FAIL（${failed} 项失败）`);
process.exitCode = failed === 0 ? 0 : 1;
