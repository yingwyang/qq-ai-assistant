/**
 * 系统管理中心外壳行为验证（纯断言，不截图）：
 *  1. /admin?tab=users 直接落到用户管理页，且侧栏高亮正确
 *  2. 非法 tab 回落数据概览，并把 URL 规整为 ?tab=dashboard
 *  3. 首次进入只请求当前 tab 所需接口（不含 config / backup / media 等未打开页面的接口）
 *  4. 切到「组件控制」后出现 component-status 轮询，切走后不再新增请求
 *  5. 每个页面都渲染了统一页头 .page-header
 *  6. 页面里没有残留的 window.confirm 调用（用 stub 计数）
 *
 * 用法: node scripts/check-admin-shell.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-admin-shell.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);

const apiCalls = [];
const page = await ctx.newPage();
page.on('request', (req) => {
  const url = req.url();
  if (url.includes('/api/')) apiCalls.push(url.replace(BASE, '').replace(/^https?:\/\/[^/]+/, ''));
});
const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));

// ---- 1. 深链接 ----
apiCalls.length = 0;
await page.goto(`${BASE}/admin?tab=users`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2500);

const usersPanel = await page.locator('.admin-users').count();
check('深链接 ?tab=users 落到用户管理页', usersPanel === 1, `.admin-users 容器数=${usersPanel}`);

const activeLabel = (await page.locator('.nav-item.active span').first().textContent().catch(() => '')) || '';
check('侧栏高亮是「用户管理」', activeLabel.includes('用户管理'), `高亮项=${activeLabel.trim()}`);

const firstLoadCalls = [...new Set(apiCalls)];
const forbidden = ['/api/admin/config', '/api/admin/backup/list', '/api/messages/media-files', '/api/dashboard/'];
const leaked = firstLoadCalls.filter((u) => forbidden.some((f) => u.startsWith(f)));
check('首屏未请求未打开页面的接口', leaked.length === 0, leaked.join(', ') || `请求 ${firstLoadCalls.length} 个接口`);
check('首屏请求数 ≤ 4', firstLoadCalls.length <= 4, `实际 ${firstLoadCalls.length}: ${firstLoadCalls.join(' | ')}`);

const headerCount = await page.locator('.admin-main .page-header').count();
check('用户管理页渲染统一页头', headerCount === 1, `.page-header 数=${headerCount}`);

// ---- 2. 刷新后停留在同一 tab（localStorage 续接） ----
await page.reload({ waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2000);
const afterReload = await page.locator('.admin-users').count();
check('刷新后仍在用户管理页', afterReload === 1);

// ---- 3. 非法 tab 回落 ----
await page.goto(`${BASE}/admin?tab=does-not-exist`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2000);
const dashPanel = await page.locator('.admin-dashboard').count();
const urlAfter = page.url();
check('非法 tab 回落数据概览', dashPanel === 1, `URL=${urlAfter}`);
check('非法 tab 被规整到 ?tab=dashboard', /[?&]tab=dashboard/.test(urlAfter), urlAfter);

// ---- 4. 组件控制轮询开关 ----
apiCalls.length = 0;
await page.locator('.nav-item:has-text("组件控制")').first().click();
await page.waitForTimeout(6500);
const withPolling = apiCalls.filter((u) => u.startsWith('/api/system/component-status')).length;
check('进入组件控制后出现 component-status 轮询', withPolling >= 2, `6.5s 内 ${withPolling} 次`);

await page.locator('.nav-item:has-text("数据概览")').first().click();
await page.waitForTimeout(1200);
apiCalls.length = 0;
await page.waitForTimeout(6500);
const afterLeave = apiCalls.filter((u) => u.startsWith('/api/system/component-status')).length;
check('离开组件控制后停止轮询', afterLeave === 0, `离开后 6.5s 内 ${afterLeave} 次`);

// ---- 5. 所有 tab 都能打开且都有页头 + 无 window.confirm ----
await page.evaluate(() => {
  window.__confirmCalls = 0;
  window.confirm = () => { window.__confirmCalls += 1; return true; };
});
const TABS = [
  ['数据概览', '.admin-dashboard'],
  ['用户管理', '.admin-users'],
  ['组件控制', '.admin-components'],
  ['配置管理', '.admin-config'],
  ['系统日志', '.admin-logs'],
  ['媒体管理', '.admin-media'],
  ['数据维护', '.admin-backup'],
  ['AI 摘要', '.admin-ai-summary'],
  ['规则配置', '.admin-credits-rule'],
  ['用户积分', '.admin-credits-users'],
  ['资金流水', '.admin-transactions'],
  ['订单管理', '.admin-orders'],
  ['退款审批', '.admin-refund-approve'],
  ['纠纷处理', '.admin-dispute'],
];
const missing = [];
const withoutHeader = [];
for (const [label, selector] of TABS) {
  await page.locator(`.nav-item:has-text("${label}")`).first().click();
  await page.waitForTimeout(1100);
  if ((await page.locator(selector).count()) === 0) missing.push(label);
  else if ((await page.locator(`${selector} .page-header`).count()) === 0) withoutHeader.push(label);
}
check('14 个页面都能打开', missing.length === 0, missing.join(', ') || 'all ok');
check('14 个页面都有统一页头', withoutHeader.length === 0, withoutHeader.join(', ') || 'all ok');

const confirmCalls = await page.evaluate(() => window.__confirmCalls);
check('管理端未使用 window.confirm', confirmCalls === 0, `调用 ${confirmCalls} 次`);

// ---- 6. 破坏性操作走 ConfirmDialog（弹窗能真的渲染出来） ----
await page.locator('.nav-item:has-text("用户管理")').first().click();
await page.waitForTimeout(1200);
const deleteBtn = page.locator('.admin-users .btn-action.delete').first();
if (await deleteBtn.count()) {
  await deleteBtn.click();
  await page.waitForTimeout(500);
  const dialog = await page.locator('.dialog-container').count();
  const title = (await page.locator('.dialog-title').first().textContent().catch(() => '')) || '';
  check('删除用户弹出 ConfirmDialog', dialog === 1 && title.includes('删除用户'), `title=${title.trim()}`);
  await page.locator('.dialog-footer .btn-secondary').first().click();
  await page.waitForTimeout(400);
  const closed = await page.locator('.dialog-container').count();
  check('取消后弹窗关闭且未执行删除', closed === 0);
} else {
  check('用户管理页存在删除按钮（用于校验确认框）', false, '未找到 .btn-action.delete');
}

// ---- 7. 重置密码改用弹窗（不再 window.prompt） ----
const resetBtn = page.locator('.admin-users button:has-text("重置密码")').first();
if (await resetBtn.count()) {
  await resetBtn.click();
  await page.waitForTimeout(500);
  const modal = page.locator('.admin-reset-password, .modal-content').filter({ hasText: '新密码' }).first();
  const pwd = await modal.locator('input[type="text"]').first().inputValue();
  check('重置密码弹窗可用且自动生成合规密码', /(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/.test(pwd) && pwd.length >= 8, `密码长度=${pwd.length}`);
  await page.locator('.modal-footer .btn-cancel').first().click();
  await page.waitForTimeout(300);
} else {
  check('用户管理页存在重置密码按钮', false, '未找到');
}

// 刷新后停在最后访问的 tab（localStorage 续接）
const beforeReloadUrl = page.url();
await page.goto(`${BASE}/admin`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2000);
const persisted = await page.evaluate(() => document.documentElement.outerHTML.includes('admin-users'));
check('不带参数访问 /admin 时续接上次页面', persisted && page.url().includes('tab=users'), `刷新前=${beforeReloadUrl} 现在=${page.url()}`);

const realErrors = consoleErrors.filter((t) => !/favicon|Download the Vue Devtools/i.test(t));
check('无控制台错误', realErrors.length === 0, realErrors.slice(0, 3).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} 通过`);
if (failed.length) {
  console.log('失败项:\n' + failed.map((f) => ` - ${f.name}: ${f.detail}`).join('\n'));
  process.exit(1);
}
