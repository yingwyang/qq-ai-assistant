/**
 * 检查「容器查询（container-type: inline-size）」是否把 position:fixed 弹窗限制在了面板内。
 * 打开转发弹窗 → 量一下遮罩尺寸，并与视口对比；再截全页图。
 * 用法：node scripts/check-overlay-containment.mjs <JWT> [baseUrl] [outDir]
 */

import { chromium } from 'playwright-core';
import path from 'node:path';
import fs from 'node:fs';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
const OUT = process.argv[4] || process.env.TEMP || '.';
fs.mkdirSync(OUT, { recursive: true });

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1600, height: 900 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();
await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3000);

const historyBtn = page.locator('.right-panel button[title="对话历史"]').first();
if (await historyBtn.count()) {
  await historyBtn.click();
  await page.waitForTimeout(1500);
}
const conv = page.locator('.conversation-item:visible').first();
if (await conv.count()) {
  await conv.click();
  await page.waitForTimeout(2000);
  const closePanel = page.locator('.conversation-panel .close-btn').first();
  if (await closePanel.count()) await closePanel.click();
  await page.waitForTimeout(500);
}

const btn = page.locator('.right-panel button:has-text("转发到群"):visible').first();
await btn.click();
await page.waitForTimeout(1200);

const viewport = page.viewportSize();
const box = await page.locator('.fw-mask').boundingBox();
const panelBox = await page.locator('.right-panel').boundingBox();
console.log('viewport =', JSON.stringify(viewport));
console.log('遮罩 fw-mask =', JSON.stringify(box));
console.log('右面板      =', JSON.stringify(panelBox));

const coversViewport = box && Math.abs(box.width - viewport.width) < 2 && Math.abs(box.height - viewport.height) < 2;
const confinedToPanel = box && panelBox && box.width <= panelBox.width + 2 && box.x >= panelBox.x - 2;
console.log(coversViewport ? '✓ 弹窗遮罩覆盖整个视口（正常）' : '✗ 弹窗遮罩没有覆盖视口');
if (confinedToPanel) console.log('✗ 遮罩被限制在右面板内（container-type 副作用）');

const file = path.join(OUT, 'overlay-check.png');
await page.screenshot({ path: file });
console.log('全页截图:', file);
await browser.close();
process.exit(coversViewport ? 0 : 1);
