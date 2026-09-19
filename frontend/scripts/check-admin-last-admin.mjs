/**
 * 「最后一位可用管理员」前端守卫校验（用网络拦截伪造接口响应，不改动真实数据）。
 *
 * 断言：
 *  1. activeAdminCount=1 时，最后那位可用管理员的「降权 / 禁用 / 删除」按钮禁用并带原因提示
 *  2. 同一页里的普通用户（USER）不受影响，提权/禁用/删除都可用
 *  3. 页面顶部出现「只有 1 位可登录的管理员」提示条
 *  4. activeAdminCount>1 时上述按钮恢复可用，提示条变为正常文案
 *
 * 为什么用拦截：达到 activeAdminCount=1 的真实状态意味着系统里只剩一位管理员，
 * 那正是要防的状态，不能为了测试把真实环境改成那样。
 *
 * 用法: node scripts/check-admin-last-admin.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-admin-last-admin.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const ADMIN_ROW = {
  id: 3, username: 'admin', nickname: '系统管理员', avatar: null,
  role: 'ADMIN', active: true,
  lastLoginTime: '2026-09-19T11:54:06', createdAt: '2026-06-14T15:59:41',
};
const USER_ROW = {
  id: 25, username: 'kkk', nickname: 'kkk', avatar: null,
  role: 'USER', active: true,
  lastLoginTime: '2026-08-27T10:36:15', createdAt: '2026-08-27T10:36:15',
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1560, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);

let activeAdminCount = 1;
await ctx.route('**/api/admin/users**', async (route) => {
  const isExport = route.request().url().includes('/export');
  if (isExport) {
    await route.fulfill({ status: 200, contentType: 'text/csv;charset=UTF-8', body: '\uFEFFID,账号\n3,admin\n' });
    return;
  }
  await route.fulfill({
    status: 200,
    contentType: 'application/json;charset=UTF-8',
    body: JSON.stringify({
      code: 200, message: 'ok', status: 'ok',
      data: {
        content: [ADMIN_ROW, USER_ROW],
        totalElements: 2, totalPages: 1, number: 0, size: 20,
        first: true, last: true,
        activeAdminCount,
      },
    }),
  });
});

const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });

const openUsersTab = async () => {
  await page.goto(`${BASE}/admin?tab=users`, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1500);
};

const rowByUsername = (username) =>
  page.locator('.admin-users tbody tr').filter({ hasText: username }).first();
const btn = (username, label) => rowByUsername(username).locator(`.btn-action:has-text("${label}")`);

// ---- 1. 只剩一位可用管理员 ----
await openUsersTab();

const bannerText = (await page.locator('.admin-users .notice-bar').textContent().catch(() => '')) || '';
check('提示条说明只剩 1 位可登录管理员', bannerText.includes('1 位可登录的管理员') && bannerText.includes('提权'),
  bannerText.replace(/\s+/g, ' ').trim().slice(0, 60));

check('最后一位管理员的「降权」被禁用', await btn('admin', '降权').isDisabled());
check('最后一位管理员的「禁用」被禁用', await btn('admin', '禁用').isDisabled());
check('最后一位管理员的「删除」被禁用', await btn('admin', '删除').isDisabled());
check('「重置密码」仍可用（它不会让人进不去后台）', !(await btn('admin', '重置密码').isDisabled()));

const tip = await btn('admin', '降权').getAttribute('title');
check('禁用按钮带原因提示', !!tip && tip.includes('至少要保留一位'), tip || '无 title');

check('普通用户的「提权」不受影响', !(await btn('kkk', '提权').isDisabled()));
check('普通用户的「禁用」不受影响', !(await btn('kkk', '禁用').isDisabled()));
check('普通用户的「删除」不受影响', !(await btn('kkk', '删除').isDisabled()));

// ---- 2. 有两位可用管理员时一切恢复正常 ----
activeAdminCount = 2;
await openUsersTab();

check('两位管理员时「降权」恢复可用', !(await btn('admin', '降权').isDisabled()));
check('两位管理员时「禁用」恢复可用', !(await btn('admin', '禁用').isDisabled()));
check('两位管理员时「删除」恢复可用', !(await btn('admin', '删除').isDisabled()));

const bannerText2 = (await page.locator('.admin-users .notice-bar').textContent().catch(() => '')) || '';
check('提示条改为正常文案', bannerText2.includes('2') && !bannerText2.includes('已被禁用'),
  bannerText2.replace(/\s+/g, ' ').trim().slice(0, 50));

check('无控制台错误', errs.length === 0, errs.slice(0, 3).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} 通过`);
if (failed.length) {
  console.log('失败项:\n' + failed.map((f) => ` - ${f.name}: ${f.detail}`).join('\n'));
  process.exit(1);
}
