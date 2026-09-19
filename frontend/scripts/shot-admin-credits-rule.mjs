/**
 * 后台「积分规则配置」页验证：加载 → 检查分节/校验/试算器 → 改一个折扣看脏标记 → 截图。
 * 用法：node scripts/shot-admin-credits-rule.mjs <JWT> [baseUrl] [outDir]
 */

import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
const OUT = process.argv[4] || process.env.TEMP || '.';
if (!TOKEN) {
  console.error('用法: node scripts/shot-admin-credits-rule.mjs <JWT> [baseUrl] [outDir]');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  —— ' + detail : ''}`);
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 1100 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();
const errors = [];
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });

await page.goto(`${BASE}/admin`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3500);
await page.locator('text=规则配置').first().click();
await page.waitForTimeout(2500);

check('分节导航渲染（6 个计费分节 + 试算）', await page.locator('.rule-nav .nav-link').count() === 7,
  `${await page.locator('.rule-nav .nav-link').count()} 项`);
check('基础奖励输入框有值', (await page.locator('#sec-base input').first().inputValue()) !== '');
check('模型倍率行编辑器存在', await page.locator('#sec-ai .sub-card .kv-row').count() >= 1);
check('阶梯折扣行编辑器存在', await page.locator('#sec-discount .sub-card').count() >= 1);
check('试算器有结果', (await page.locator('.result-card .result-cost').first().innerText()).match(/\d+/) !== null,
  (await page.locator('.result-card .result-cost').first().innerText()).replace(/\s+/g, ' '));
check('初始状态为「已同步」', await page.locator('.clean-badge').count() === 1);

// 改一个折扣 → 出现脏标记
const discountInput = page.locator('#sec-discount input[type="number"]').first();
await discountInput.fill('0.85');
await page.waitForTimeout(500);
check('改动后出现「有未保存的修改」', await page.locator('.dirty-badge').count() === 1);
const costAfter = await page.locator('.result-card .result-cost').first().innerText();
check('试算结果随改动刷新', costAfter.match(/\d+/) !== null, costAfter.replace(/\s+/g, ' '));

// 试算器：改输入 tokens，费用应变大
const before = parseInt((await page.locator('.result-card .result-cost').first().innerText()).replace(/\D/g, ''), 10);
await page.locator('.preview-inputs input[type="number"]').first().fill('4000');
await page.waitForTimeout(400);
const after = parseInt((await page.locator('.result-card .result-cost').first().innerText()).replace(/\D/g, ''), 10);
check('输入 tokens 增加 → 费用上升', after > before, `${before} → ${after}`);

// 非法折扣 → 内联报错 + 保存禁用
await discountInput.fill('1.5');
await page.waitForTimeout(400);
check('非法折扣触发内联校验', await page.locator('.field.bad').count() >= 1);
check('存在错误时保存按钮禁用', await page.locator('.rule-toolbar-right .btn-action.promote').isDisabled());

// 恢复合法值 + 放弃修改
await discountInput.fill('0.8');
await page.waitForTimeout(300);
await page.locator('button:has-text("放弃修改")').click();
await page.waitForTimeout(600);
check('放弃修改后回到已同步', await page.locator('.clean-badge').count() === 1);

// 截图（亮/暗）
const setTheme = async (t) => {
  await page.evaluate((th) => {
    const root = document.querySelector('.app') || document.querySelector('#app')?.firstElementChild;
    [document.documentElement, root].filter(Boolean).forEach((el) => {
      el.classList.remove('theme-light', 'theme-dark');
      el.classList.add(`theme-${th}`);
    });
  }, t);
  await page.waitForTimeout(700);
};
const shots = [];
for (const theme of ['light', 'dark']) {
  await setTheme(theme);
  const file = path.join(OUT, `admin-credits-rule-${theme}.png`);
  await page.locator('.admin-credits-rule').first().screenshot({ path: file });
  shots.push(file);
}

if (errors.length) console.log('控制台错误:', errors.slice(0, 5));
console.log('截图:\n' + shots.join('\n'));
const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
await browser.close();
process.exit(failed.length ? 1 : 0);
