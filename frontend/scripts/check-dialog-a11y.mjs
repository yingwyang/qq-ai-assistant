/**
 * 共享确认弹窗的无障碍与键盘操作验收（批次 F.4）。
 *
 * 背景：项目所有破坏性操作都走 ConfirmDialog，之前只有鼠标可用——
 * 没有 role/aria 标记、打开后焦点仍在背后页面、Esc 关不掉。
 * 本轮补齐：role="alertdialog" + aria-modal + aria-labelledby/describedby、
 * 打开聚焦确认按钮、关闭归还焦点、Esc 关闭。
 *
 * 用法: node scripts/check-dialog-a11y.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-dialog-a11y.mjs <JWT> [baseUrl]');
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

const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });

// 用「用户管理 → 删除」这个真实破坏性入口打开共享弹窗
await page.goto(`${BASE}/admin?tab=users`, { waitUntil: 'domcontentloaded' });
await page.waitForSelector('.admin-users tbody tr', { timeout: 15000 });

const row = page.locator('.admin-users tbody tr').filter({ hasNotText: 'admin' }).first();
await row.locator('.btn-action:has-text("删除")').first().click();
await page.waitForSelector('.dialog-container', { timeout: 8000 });

const dialog = page.locator('.dialog-container');
check('弹窗打开', await dialog.isVisible());

// 1. 无障碍标记
const role = await dialog.getAttribute('role');
const ariaModal = await dialog.getAttribute('aria-modal');
const labelledBy = await dialog.getAttribute('aria-labelledby');
const describedBy = await dialog.getAttribute('aria-describedby');
check('role=alertdialog', role === 'alertdialog', `实际 ${role}`);
check('aria-modal=true', ariaModal === 'true', `实际 ${ariaModal}`);

const titleId = await page.locator('.dialog-title').getAttribute('id');
const msgId = await page.locator('.dialog-message').getAttribute('id');
check('aria-labelledby/describedby 指向标题与正文', labelledBy === titleId && describedBy === msgId,
  `labelledby=${labelledBy}/${titleId} describedby=${describedBy}/${msgId}`);

// 2. 打开后焦点落在确认按钮
const focusedText = await page.evaluate(() => document.activeElement?.textContent?.trim() || '');
const focusedInDialog = await page.evaluate(() => !!document.activeElement?.closest('.dialog-container'));
check('打开后焦点进入弹窗（默认在确认按钮）', focusedInDialog, `当前焦点文本：${focusedText}`);

// 3. Esc 关闭
await page.keyboard.press('Escape');
await page.waitForTimeout(400);
check('Esc 可关闭弹窗', (await page.locator('.dialog-container').count()) === 0);

// 4. 关闭后焦点归还触发元素（不再停留在 body）
const focusBack = await page.evaluate(() => document.activeElement?.className || document.activeElement?.tagName || '');
check('关闭后焦点归还触发处', !String(focusBack).includes('body') && String(focusBack) !== 'BODY',
  `焦点元素：${focusBack}`);

check('无 JS 报错', errs.length === 0, errs.slice(0, 2).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
