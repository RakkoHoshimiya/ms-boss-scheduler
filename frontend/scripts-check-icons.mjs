// 构建后校验：每个 BOSS 用到的图标文件都必须存在，防止线上出现裂图
import fs from 'node:fs';
import path from 'node:path';

const ICON_DIR = path.resolve('public/boss-icons');
const REQUIRED = [
  'seren', 'kalos', 'kaling', 'adversary', 'evilstar', 'limbo',
  'lotus', 'damien', 'slimeking', 'blackmage',
  'lucid', 'will', 'gloom', 'hilla', 'darknell',
];

const files = fs.existsSync(ICON_DIR) ? fs.readdirSync(ICON_DIR) : [];
const missing = REQUIRED.filter((key) => !files.includes(`${key}.png`));
const broken = files
  .filter((f) => f.endsWith('.png'))
  .filter((f) => {
    const buf = fs.readFileSync(path.join(ICON_DIR, f));
    // 必须是 PNG 且尺寸合理（防止把 404 的 HTML 存成 .png）
    return !(buf.length > 100 && buf.readUInt32BE(0) === 0x89504e47 && buf.readUInt32BE(16) >= 16);
  });

console.log(`图标目录: ${files.length} 个文件`);
if (missing.length) {
  console.log('缺少图标: ' + missing.join(', '));
}
if (broken.length) {
  console.log('异常图标（非 PNG 或尺寸过小）: ' + broken.join(', '));
}
if (missing.length || broken.length) {
  console.log('ICON_CHECK_FAIL');
  process.exit(1);
}
console.log('ICON_CHECK_PASS');
