/**
 * 数据完整性校验（正式版回归工具）。
 *
 * 校验线上数据是否符合业务规则，用于发版前 / 定期巡检：
 *   1. memberCount 字段与实际成员数一致、且不超容量
 *   2. 同一 BOSS + 同周期 + 同难度下 runIndex 不重复
 *   3. 同一车次内不出现重复角色、不出现同一玩家的两个角色
 *   4. 同一角色不在同周期同一 BOSS 的多趟车上
 *   5. 融合车：同一趟在全部成员 BOSS 上都有记录，且阵容完全一致
 *
 * 用法：
 *   GMSR_TOKEN=<管理员JWT> node scripts-check-integrity.mjs
 *
 * 退出码：0 = 全部通过，1 = 发现问题，2 = 用法错误。
 */
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0'; // 服务器用私有 CA 自签证书

const BACKEND = process.env.GMSR_BACKEND || 'https://<YOUR_SERVER_IP>:4433';
const TOKEN = process.env.GMSR_TOKEN || '';
const API = BACKEND + '/api';
const H = { Authorization: 'Bearer ' + TOKEN };

if (!TOKEN) {
  console.error('缺少 GMSR_TOKEN。先取一个管理员 token：');
  console.error(
    `  curl -sk -X POST ${BACKEND}/api/auth/login -H 'Content-Type: application/json' -d '{"username":"admin","password":"..."}'`,
  );
  process.exit(2);
}

const issues = [];
const note = (level, msg) => {
  issues.push(`${level} | ${msg}`);
  console.log(`  [${level}] ${msg}`);
};

/** 找出数组里重复出现的元素（排除自身比较）。 */
const duplicates = (list) => {
  const seen = new Set();
  const dup = new Set();
  for (const item of list) {
    if (seen.has(item)) {
      dup.add(item);
    }
    seen.add(item);
  }
  return [...dup];
};

const get = async (path) => {
  const res = await fetch(API + path, { headers: H });
  if (!res.ok) {
    throw new Error(`GET ${path} → HTTP ${res.status}`);
  }
  return res.json();
};

const bosses = await get('/bosses');
const bossById = new Map(bosses.map((b) => [b.id, b]));
const weeks = await get('/weeks/triples');

const combos = new Map();
for (const boss of bosses.filter((b) => b.comboKey)) {
  if (!combos.has(boss.comboKey)) {
    combos.set(boss.comboKey, []);
  }
  combos.get(boss.comboKey).push(boss);
}

for (const slot of ['lastWeek', 'thisWeek', 'nextWeek']) {
  const weekKey = weeks[slot].weekKey;
  const raw = await get(`/weeks/${weekKey}/runs`);
  const runs = Array.isArray(raw) ? raw : raw.runs || [];
  console.log(`\n=== 周期 ${weekKey}（${runs.length} 条车次）`);

  for (const run of runs) {
    const capacity = run.capacity || (bossById.get(run.bossId) || {}).capacity || 6;
    const real = (run.members || []).length;
    if (run.memberCount !== real) {
      note('ERROR', `第${run.runIndex}趟 ${run.bossName} memberCount=${run.memberCount} 但实际 ${real} 个成员`);
    }
    if (real > capacity) {
      note('ERROR', `第${run.runIndex}趟 ${run.bossName} 超员：${real} > 容量 ${capacity}`);
    }
  }

  const seenIndex = new Map();
  for (const run of runs) {
    const key = `${run.bossId}|${run.tier}|${run.runIndex}`;
    if (seenIndex.has(key)) {
      note(
        'ERROR',
        `runIndex 重复：${run.bossName} ${run.tier} 第${run.runIndex}趟（runId=${seenIndex.get(key)} 与 ${run.id}）`,
      );
    } else {
      seenIndex.set(key, run.id);
    }
  }

  for (const run of runs) {
    const dupChar = duplicates((run.members || []).map((m) => m.characterId));
    if (dupChar.length) {
      note('ERROR', `第${run.runIndex}趟 ${run.bossName} 同一角色重复：charId=${dupChar.join(',')}`);
    }
    const dupPlayer = duplicates((run.members || []).map((m) => m.playerId));
    if (dupPlayer.length) {
      const who = (run.members || [])
        .filter((m) => dupPlayer.includes(m.playerId))
        .map((m) => m.playerNickname)
        .join('/');
      note(
        'ERROR',
        `第${run.runIndex}趟 ${run.bossName} 同一玩家两个角色：${who}（playerId=${dupPlayer.join(',')}）`,
      );
    }
  }

  const charSeen = new Map();
  for (const run of runs) {
    for (const member of run.members || []) {
      const key = `${run.bossId}|${member.characterId}`;
      const prev = charSeen.get(key);
      if (prev && prev.runId !== run.id) {
        // 同一 BOSS 的不同难度算两场独立的车，分别上车是合理的；
        // 只有同难度下重复上车才是错误。
        const level = prev.tier === run.tier ? 'ERROR' : 'INFO';
        note(
          level,
          `${member.playerNickname}/${member.job} 同时出现在 ${run.bossName} 第${prev.runIndex}趟(${prev.tier})和第${run.runIndex}趟(${run.tier})`,
        );
      } else if (!prev) {
        charSeen.set(key, { runId: run.id, runIndex: run.runIndex, tier: run.tier });
      }
    }
  }
}

for (const [comboKey, members] of combos) {
  console.log(`\n=== 融合车 ${comboKey}（${members.length} 个成员 BOSS）`);
  for (const slot of ['lastWeek', 'thisWeek', 'nextWeek']) {
    const weekKey = weeks[slot].weekKey;
    const raw = await get(`/weeks/${weekKey}/runs`);
    const runs = (Array.isArray(raw) ? raw : raw.runs || []).filter((r) =>
      members.some((m) => m.id === r.bossId),
    );
    const byIndex = new Map();
    for (const run of runs) {
      if (!byIndex.has(run.runIndex)) {
        byIndex.set(run.runIndex, []);
      }
      byIndex.get(run.runIndex).push(run);
    }
    if (byIndex.size === 0) {
      console.log(`  ${weekKey}: 无融合车`);
      continue;
    }
    for (const [runIndex, group] of byIndex) {
      if (group.length !== members.length) {
        const missing = members
          .filter((m) => !group.some((g) => g.bossId === m.id))
          .map((m) => m.name)
          .join('、');
        note(
          'WARN',
          `${weekKey} 融合车第${runIndex}趟只有 ${group.length}/${members.length} 个 BOSS 有记录（缺：${missing}）`,
        );
      }
      const signatures = group.map((g) =>
        JSON.stringify((g.members || []).map((m) => m.characterId).sort()),
      );
      if (new Set(signatures).size > 1) {
        note(
          'ERROR',
          `${weekKey} 融合车第${runIndex}趟 成员 BOSS 阵容不一致：` +
            group.map((g) => `${g.bossName}=${(g.members || []).length}人`).join(', '),
        );
      }
    }
  }
}

console.log(`\n=== 汇总：${issues.length} 个问题`);
if (issues.length === 0) {
  console.log('  ✓ 数据完整性校验全部通过');
  console.log('INTEGRITY_CHECK_PASS');
} else {
  console.log('INTEGRITY_CHECK_FAIL');
  process.exitCode = 1;
}
