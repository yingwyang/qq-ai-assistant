/**
 * 顶栏视觉验证：登录 → 打开 AstrBot 面板 → 分别截亮色/暗色顶栏特写。
 * 用法：node scripts/shot-astrbot-header.mjs <JWT> [baseUrl] [outDir]
 */

import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
const OUT = process.argv[4] || process.env.TEMP || '.';
if (!TOKEN) {
  console.error('用法: node scripts/shot-astrbot-header.mjs <JWT> [baseUrl] [outDir]');
  process.exit(2);
}
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const context = await browser.newContext({ viewport: { width: 1600, height: 900 } });
await context.addCookies([{
  name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax',
}]);
const page = await context.newPage();
await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3000);

// 展开 QQ 绑定 → 点第一个群（右侧 AstrBot 面板才有上下文）
const binding = page.locator('.qq-binding-header, .qq-info-row, .binding-header').first();
if (await binding.count()) await binding.click({ timeout: 3000 }).catch(() => {});
await page.waitForTimeout(800);
const group = page.locator('.group-item:visible').first();
if (await group.count()) {
  await group.click();
  await page.waitForTimeout(2500);
}

// 进入一个历史对话，让副标题有内容
const historyBtn = page.locator('.right-panel button[title="对话历史"]').first();
if (await historyBtn.count()) {
  await historyBtn.click();
  await page.waitForTimeout(1800);
}
const conv = page.locator('.conversation-item:visible').first();
if (await conv.count()) {
  await conv.click();
  await page.waitForTimeout(2000);
  const closePanel = page.locator('.conversation-panel .close-btn').first();
  if (await closePanel.count()) await closePanel.click();
  await page.waitForTimeout(600);
}

const header = page.locator('.right-panel .chat-header').first();
await header.waitFor({ timeout: 10000 });

const shots = [];
for (const theme of ['light', 'dark']) {
  // 主题类在应用根节点上（App.vue 的 .app），只改 <html> 会被根节点上的旧类覆盖
  await page.evaluate((t) => {
    const root = document.querySelector('.app') || document.querySelector('#app')?.firstElementChild;
    const nodes = [document.documentElement, root, ...document.querySelectorAll('.app > .theme-light, .app > .theme-dark')];
    nodes.filter(Boolean).forEach((el) => {
      el.classList.remove('theme-light', 'theme-dark');
      el.classList.add(`theme-${t}`);
    });
  }, theme);
  await page.waitForTimeout(700);
  const file = path.join(OUT, `astrbot-header-${theme}.png`);
  await header.screenshot({ path: file });
  shots.push(file);
  // 顺带截整块右面板，便于看与消息区的衔接
  const panel = page.locator('.right-panel').first();
  const panelFile = path.join(OUT, `astrbot-panel-${theme}.png`);
  await panel.screenshot({ path: panelFile });
  shots.push(panelFile);
}

const text = await header.innerText();
console.log('顶栏文本:\n' + text);

// 窄面板：把右栏临时压到 22%，验证容器查询下的降级布局
await page.evaluate(() => {
  const panel = document.querySelector('.right-panel');
  if (panel) panel.style.flex = '0 0 22%';
  window.dispatchEvent(new Event('resize'));
});
await page.waitForTimeout(500);
for (const theme of ['light', 'dark']) {
  await page.evaluate((t) => {
    const root = document.querySelector('.app') || document.querySelector('#app')?.firstElementChild;
    [document.documentElement, root].filter(Boolean).forEach((el) => {
      el.classList.remove('theme-light', 'theme-dark');
      el.classList.add(`theme-${t}`);
    });
  }, theme);
  await page.waitForTimeout(500);
  const file = path.join(OUT, `astrbot-header-narrow-${theme}.png`);
  await header.screenshot({ path: file });
  shots.push(file);
}

console.log('\n截图:\n' + shots.join('\n'));
await browser.close();
