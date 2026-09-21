/**
 * 列表页状态一致性守卫（批次 F.2）。
 *
 * 规则：
 *  1. 共享组件 components/common/StatePanel.vue 必须存在，且支持 loading/empty/error 三种状态；
 *  2. 下列列表页必须引用 StatePanel（迁移后不允许再自绘状态）；
 *  3. 旧的分散 class（audit-loading / audit-empty / orders-loading / orders-empty）不得再出现在页面里，
 *     只允许出现在 StatePanel 自身的说明注释中。
 *
 * 用法: node scripts/check-shared-states.mjs
 */

import fs from 'node:fs';
import path from 'node:path';

const SRC = path.resolve('src');
const COMPONENT = path.join(SRC, 'components', 'common', 'StatePanel.vue');

/** 必须使用共享状态面板的列表页 */
const MUST_USE = [
  'views/admin/AdminUsers.vue',
  'views/admin/AdminCreditsUsers.vue',
  'views/admin/AdminLogs.vue',
  'views/admin/AdminOrders.vue',
  'views/admin/AdminDispute.vue',
  'views/admin/AdminRefundApprove.vue',
  'views/admin/AdminTransactions.vue',
  'views/AdminView.vue',
  'components/credits/SubscriptionDashboard.vue',
];

/** 已废弃的分散 class（只允许出现在共享组件注释里） */
const LEGACY_CLASSES = ['audit-loading', 'audit-empty', 'orders-loading', 'orders-empty'];

const problems = [];

// 1. 共享组件存在性与能力
if (!fs.existsSync(COMPONENT)) {
  problems.push('缺少共享状态组件 src/components/common/StatePanel.vue');
} else {
  const code = fs.readFileSync(COMPONENT, 'utf8');
  for (const state of ['loading', 'empty', 'error']) {
    if (!code.includes(`'${state}'`)) problems.push(`StatePanel 未声明 ${state} 状态`);
  }
  if (!code.includes('@action')) problems.push('StatePanel 未提供 action 事件（重试/CTA 按钮）');
}

// 2. 列表页必须引用
for (const rel of MUST_USE) {
  const file = path.join(SRC, rel);
  if (!fs.existsSync(file)) { problems.push(`目标页面不存在：${rel}`); continue; }
  const code = fs.readFileSync(file, 'utf8');
  if (!code.includes('StatePanel')) problems.push(`${rel} 未使用共享 StatePanel`);
}

// 3. 旧 class 不得残留（组件自身注释除外）
function walk(dir) {
  const out = [];
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) out.push(...walk(p));
    else if (/\.(vue|css)$/.test(e.name)) out.push(p);
  }
  return out;
}

for (const file of walk(SRC)) {
  if (path.resolve(file) === path.resolve(COMPONENT)) continue;
  const code = fs.readFileSync(file, 'utf8');
  for (const cls of LEGACY_CLASSES) {
    if (code.includes(cls)) {
      problems.push(`${path.relative(process.cwd(), file)} 仍在使用旧状态 class「${cls}」`);
    }
  }
}

if (problems.length) {
  console.error('❌ 列表页状态一致性检查未通过：');
  problems.forEach((p) => console.error('   - ' + p));
  process.exit(1);
}
console.log(`✅ 状态一致性检查通过：StatePanel 支撑 loading/empty/error，${MUST_USE.length} 个列表页均已迁移，无旧 class 残留`);
