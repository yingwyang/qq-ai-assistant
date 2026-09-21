/**
 * 前端一致性守卫：禁止原生弹窗（window.confirm / alert / prompt）。
 *
 * 背景（企业级化批次 F）：项目统一使用 ConfirmDialog / Toast / 业务弹窗，
 * 原生弹窗视觉与交互割裂、无法做无障碍与主题适配，也无法在自动化验收里断言。
 * 本次清理了 ChatInterface（删除消息两步确认）、UserCenter（解绑 QQ）、
 * SubscriptionDashboard（复制回退）三处残留；这里用源码扫描把约定固化，防止再写回去。
 *
 * 用法: node scripts/check-no-native-dialogs.mjs
 */

import fs from 'node:fs';
import path from 'node:path';

const SRC = path.resolve('src');
const EXT = new Set(['.vue', '.js']);

/** 去掉注释后再匹配，避免把说明文字（例如"早期用 window.prompt"）当成违规 */
function stripComments(code) {
  return code
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');
}

const PATTERNS = [
  { re: /window\.confirm\s*\(/g, what: 'window.confirm' },
  { re: /window\.alert\s*\(/g, what: 'window.alert' },
  { re: /window\.prompt\s*\(/g, what: 'window.prompt' },
  { re: /(^|[^.\w])alert\s*\(/g, what: 'alert()' },
];

function walk(dir) {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, entry.name);
    if (entry.isDirectory()) out.push(...walk(p));
    else if (EXT.has(path.extname(entry.name))) out.push(p);
  }
  return out;
}

const violations = [];
for (const file of walk(SRC)) {
  const code = stripComments(fs.readFileSync(file, 'utf8'));
  const lines = code.split(/\r?\n/);
  for (const { re, what } of PATTERNS) {
    lines.forEach((line, idx) => {
      re.lastIndex = 0;
      if (re.test(line)) {
        violations.push(`${path.relative(process.cwd(), file)}:${idx + 1} 使用了原生弹窗 ${what}`);
      }
    });
  }
}

if (violations.length > 0) {
  console.error('❌ 发现原生弹窗残留（请改用 ConfirmDialog / Toast）：');
  violations.forEach((v) => console.error('   - ' + v));
  process.exit(1);
}
console.log('✅ 前端无原生弹窗残留（window.confirm / alert / prompt 计数为 0）');
