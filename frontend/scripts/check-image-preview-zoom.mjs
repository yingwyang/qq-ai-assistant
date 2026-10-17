/**
 * 图片浏览（共享 lightbox）缩放能力守卫。
 *
 * 背景：lightbox 此前只能按窗口大小看一张图 —— 小图看不清细节、长图缩不到一屏、不能拖看局部。
 * 三个入口（聊天图片 / 管理端媒体 / 用户中心）都走同一份 composables/useImagePreview +
 * components/ImagePreview.vue，所以这组断言覆盖了全站的图片浏览。
 *
 * 用法: node scripts/check-image-preview-zoom.mjs <ADMIN_JWT> [baseUrl]
 */
import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-image-preview-zoom.mjs <ADMIN_JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log((ok ? '✅' : '❌') + ' ' + name + (detail ? ' — ' + detail : ''));
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();

const zoomValue = () => page.locator('.lp-zoom-value').innerText();
/** 连点到禁用为止：到上限/下限后按钮会 disabled，硬点会等 enabled 而超时 */
const clickUntilDisabled = async (loc, max = 30) => {
  for (let i = 0; i < max; i++) {
    if (await loc.isDisabled()) return i;
    await loc.click();
  }
  return max;
};
const imgStyle = () => page.locator('.lp-image').getAttribute('style');
const scaleOf = async () => {
  const s = String(await imgStyle() || '');
  const m = s.match(/scale\(([\d.]+)\)/);
  return m ? Number(m[1]) : null;
};

// ---- 打开：管理端媒体 → 单图预览 → 点图进共享 lightbox ----
await page.goto(BASE + '/admin?tab=media', { waitUntil: 'domcontentloaded' });
// 注意：视频/音频行的占位 span 也带 .media-thumb 这个 class（见 AdminMedia.vue），
// 列表第一行若不是图片，点它什么也不会发生 —— 全量跑时曾因此偶发超时。
// 所以按「含图片缩略图的行」定位，并用该行的「预览」按钮打开。
const imageRow = page.locator('tbody tr').filter({ has: page.locator('img.media-thumb') }).first();
await imageRow.waitFor({ state: 'visible', timeout: 25000 });
await imageRow.locator('.btn-preview').click();
const single = page.locator('.preview-img').first();
await single.waitFor({ state: 'visible', timeout: 15000 });
await single.click();
await page.locator('.lp-lightbox').waitFor({ state: 'visible', timeout: 15000 });
await page.waitForTimeout(400);
check('点击媒体图片能打开共享 lightbox', await page.locator('.lp-lightbox').isVisible());

// ---- 控件存在性 + 可访问名 ----
const toolbar = page.locator('.lp-zoom');
check('lightbox 出现缩放工具条', await toolbar.isVisible());
check('工具条有分组语义（role=group + aria-label）',
  (await toolbar.getAttribute('role')) === 'group' && !!(await toolbar.getAttribute('aria-label')));
check('「缩小」按钮有 aria-label', (await page.locator('.lp-zoom-btn[aria-label="缩小"]').count()) === 1);
check('「放大」按钮有 aria-label', (await page.locator('.lp-zoom-btn[aria-label="放大"]').count()) === 1);
check('初始为 100%', (await zoomValue()).trim() === '100%', await zoomValue());

// ---- 放大 ----
await page.locator('.lp-zoom-btn[aria-label="放大"]').click();
check('点「放大」→ 125%', (await zoomValue()).trim() === '125%', await zoomValue());
check('图片实际被放大（scale 1.25）', (await scaleOf()) === 1.25, 'scale=' + (await scaleOf()));

// ---- 键盘 + ----
await page.keyboard.press('Equal');
check('快捷键 + → 156%', (await zoomValue()).trim() === '156%', await zoomValue());

// ---- 点百分比复位 ----
await page.locator('.lp-zoom-value').click();
check('点百分比 → 复位 100%', (await zoomValue()).trim() === '100%', await zoomValue());
check('复位后 scale 回到 1', (await scaleOf()) === 1, 'scale=' + (await scaleOf()));

// ---- 缩小（小于 1 也允许）----
await page.keyboard.press('Minus');
check('快捷键 - → 80%', (await zoomValue()).trim() === '80%', await zoomValue());

// ---- 滚轮 ----
await page.mouse.move(720, 450);
await page.mouse.wheel(0, -120);
await page.waitForTimeout(150);
check('滚轮向上 → 放大到 100%', (await zoomValue()).trim() === '100%', await zoomValue());

// ---- 上下限钳制 ----
const plus = page.locator('.lp-zoom-btn[aria-label="放大"]');
await clickUntilDisabled(plus);
check('放大到上限 500%', (await zoomValue()).trim() === '500%', await zoomValue());
check('到上限后「放大」按钮禁用', await plus.isDisabled());
const minus = page.locator('.lp-zoom-btn[aria-label="缩小"]');
await clickUntilDisabled(minus);
check('缩小到下限 50%', (await zoomValue()).trim() === '50%', await zoomValue());
check('到下限后「缩小」按钮禁用', await minus.isDisabled());

// ---- 拖拽平移（仅放大时生效）----
await page.locator('.lp-zoom-value').click();
for (let i = 0; i < 3; i++) await plus.click();   // 100% → 195%（未到上限，可安全连点）
const box = await page.locator('.lp-image').boundingBox();
await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
await page.mouse.down();
await page.mouse.move(box.x + box.width / 2 + 60, box.y + box.height / 2 + 30, { steps: 5 });
await page.mouse.up();
await page.waitForTimeout(120);
const moved = String(await imgStyle() || '').match(/translate\((-?\d+)px, (-?\d+)px\)/);
check('放大后可拖动平移', !!moved && (Number(moved[1]) !== 0 || Number(moved[2]) !== 0),
  moved ? 'translate=' + moved[1] + ',' + moved[2] : '未取到 translate');

// ---- 关闭与复位 ----
await page.keyboard.press('Escape');
await page.waitForTimeout(250);
check('ESC 能关闭 lightbox', (await page.locator('.lp-lightbox').count()) === 0);
check('ESC 只关最上层 lightbox，下层媒体预览弹窗保留',
  await page.locator('.preview-modal').isVisible());
await single.click();
await page.locator('.lp-lightbox').waitFor({ state: 'visible', timeout: 15000 });
await page.waitForTimeout(300);
check('重新打开时缩放已复位为 100%', (await zoomValue()).trim() === '100%', await zoomValue());

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log('');
console.log('通过 ' + (results.length - failed.length) + '/' + results.length);
if (failed.length) {
  failed.forEach((f) => console.log('  - ' + f.name + (f.detail ? ' — ' + f.detail : '')));
  process.exit(1);
}
