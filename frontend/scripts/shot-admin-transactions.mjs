/**
 * 后台「资金流水」页视觉验证：登录 → 后台 → 积分/资金流水 → 截图（亮/暗两套）。
 * 用法：node scripts/shot-admin-transactions.mjs <JWT> [baseUrl] [outDir]
 */

import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
const OUT = process.argv[4] || process.env.TEMP || '.';
if (!TOKEN) {
  console.error('用法: node scripts/shot-admin-transactions.mjs <JWT> [baseUrl] [outDir]');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

await page.goto(`${BASE}/admin`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3500);

// 左侧菜单里点「积分流水」
const menu = page.locator('text=积分流水').first();
if (await menu.count()) {
  await menu.click();
  await page.waitForTimeout(3000);
} else {
  console.log('⚠ 没找到「积分流水」菜单项');
}

const setTheme = async (t) => {
  await page.evaluate((th) => {
    const root = document.querySelector('.app') || document.querySelector('#app')?.firstElementChild;
    [document.documentElement, root].filter(Boolean).forEach((el) => {
      el.classList.remove('theme-light', 'theme-dark');
      el.classList.add(`theme-${th}`);
    });
  }, t);
  await page.waitForTimeout(600);
};

const shots = [];
for (const theme of ['light', 'dark']) {
  await setTheme(theme);
  const panel = page.locator('.admin-transactions').first();
  if (await panel.count()) {
    const file = path.join(OUT, `admin-transactions-${theme}.png`);
    await panel.screenshot({ path: file });
    shots.push(file);
  }
}

// 断言关键元素确实渲染了
const checks = {
  kpiCards: await page.locator('.admin-transactions .kpi-card').count(),
  charts: await page.locator('.admin-transactions .chart-body canvas').count(),
  cashCells: await page.locator('.admin-transactions .cash-in-cell, .admin-transactions .cash-out-cell').count(),
  presetButtons: await page.locator('.admin-transactions .preset-btn').count(),
  manualEntryBtn: await page.locator('.admin-transactions button:has-text("手工记账")').count(),
};
console.log('元素检查:', JSON.stringify(checks));
if (errors.length) console.log('控制台错误:', errors.slice(0, 5));
console.log('截图:\n' + shots.join('\n'));
await browser.close();
