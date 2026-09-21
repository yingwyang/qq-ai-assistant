/**
 * 列表页「错误态 + 重试」验收（批次 F.2）。
 *
 * 背景：迁移前错误与空态共用同一样式（audit-empty），接口失败时页面显示"暂无数据"，
 * 用户与运维都分不清"没有数据"还是"请求失败"。迁移后错误态由 StatePanel 的 error 变体承担，
 * 并挂真实加载函数作为「重试」。
 *
 * 本脚本用网络拦截伪造一次 500，验证：
 *  1. 页面显示错误态（.state-panel.is-error），且文案非空；
 *  2. 出现「重试」按钮；
 *  3. 恢复接口后点「重试」，列表正常加载出数据行。
 *
 * 用法: node scripts/check-error-state.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-error-state.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1560, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);

let failUsersApi = true;
await ctx.route('**/api/admin/users**', async (route) => {
  if (failUsersApi) {
    await route.fulfill({ status: 500, contentType: 'application/json;charset=UTF-8',
      body: JSON.stringify({ code: 500, message: '模拟服务端错误', status: 'error', errorCode: 'INTERNAL_ERROR' }) });
    return;
  }
  await route.continue();
});

const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(e.message));

await page.goto(`${BASE}/admin?tab=users`, { waitUntil: 'domcontentloaded' });

// 1. 错误态
try {
  await page.waitForSelector('.state-panel.is-error', { timeout: 12000 });
  const text = (await page.locator('.state-panel.is-error').innerText()).replace(/\s+/g, ' ').trim();
  check('接口失败时显示错误态（与空态区分）', text.length > 0, text.slice(0, 60));
} catch (e) {
  check('接口失败时显示错误态（与空态区分）', false, '未出现 .state-panel.is-error');
}

// 2. 重试按钮
const retry = page.locator('.state-panel.is-error .state-action');
const hasRetry = (await retry.count()) > 0;
check('错误态提供「重试」按钮', hasRetry, hasRetry ? (await retry.innerText()).trim() : '缺少按钮');

// 3. 恢复接口后点重试 → 列表出现数据行
if (hasRetry) {
  failUsersApi = false;
  await retry.click();
  try {
    await page.waitForSelector('.admin-users tbody tr', { timeout: 12000 });
    const rows = await page.locator('.admin-users tbody tr').count();
    check('点「重试」后列表恢复加载', rows > 0, `数据行 ${rows}`);
  } catch (e) {
    check('点「重试」后列表恢复加载', false, '重试后仍无数据行');
  }
}

check('全程无 JS 报错', errs.length === 0, errs.slice(0, 2).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
