/**
 * 支付链路验收：支付步骤必须是「按钮」，不能是二维码 / 扫码。
 *
 * 断言（对应需求「支付用的二维码替换成按钮」）：
 *  1. 订阅页套餐卡片可点，弹出「确认订单」弹窗（不是浏览器原生 confirm）
 *  2. 弹窗含步骤条：确认订单 → 完成付款 → 管理员确认到账
 *  3. 支付方式是按钮组：人工确认收款（默认选中）/ 在线支付（未开通、禁用）
 *  4. 弹窗内不存在任何二维码元素，也没有「二维码」字样
 *  5. 未勾选协议时「确认支付」按钮禁用；勾选后可用且文案带金额
 *  6. 点击确认支付只提交一次，请求体为 { planCode, paymentMethod: 'MANUAL' }
 *  7. 弹窗第二步显示订单号 + 状态「待确认收款」+ 查看订单详情/完成 按钮
 *  8. 订单状态筛选下拉与状态列同源，不再出现旧的「待支付」文案
 *  9. 全流程无 JS 报错
 *
 * 安全性：/api/subscriptions/purchase 被网络拦截伪造，不会产生真实订单；
 *        其余读接口走真实后端（只读）。
 *
 * 用法: node scripts/check-payment-flow.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-payment-flow.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const FAKE_ORDER_NO = 'QQAICHECK20260920T0001';

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅' : '❌'} ${name}${detail ? ' — ' + detail : ''}`);
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1560, height: 1000 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);

const purchaseCalls = [];
await ctx.route('**/api/subscriptions/purchase', async (route) => {
  purchaseCalls.push(route.request().postDataJSON());
  await route.fulfill({
    status: 200,
    contentType: 'application/json;charset=UTF-8',
    body: JSON.stringify({
      code: 200, message: 'ok', status: 'ok',
      data: {
        orderNo: FAKE_ORDER_NO,
        status: 'PENDING',
        message: '订单已提交，等待管理员确认收款',
        pointsGranted: 3000,
        amount: 30,
      },
    }),
  });
});

const page = await ctx.newPage();
const errs = [];
page.on('pageerror', (e) => errs.push(e.message));
page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text()); });

// 1. 打开订阅页并进入套餐弹窗
await page.goto(`${BASE}/user-center?tab=subscription`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2500);
const upgradeBtn = page.locator('.btn-upgrade, .btn-upgrade-sm').first();
await upgradeBtn.click();
await page.waitForSelector('.upgrade-dialog', { timeout: 8000 });
const planBtn = page.locator('.upgrade-dialog .plan-card-btn:not([disabled])').first();
const planName = (await page.locator('.upgrade-dialog .plan-card').first().locator('.plan-card-name').innerText()).trim();
await planBtn.click();
await page.waitForSelector('.pay-dialog', { timeout: 8000 });
check('点击「立即购买」弹出确认订单弹窗（非原生 confirm）', await page.locator('.pay-dialog').isVisible());

// 2. 步骤条
const stepLabels = await page.locator('.pay-dialog .pay-step-label').allInnerTexts();
check('弹窗含三步流程', stepLabels.length === 3, stepLabels.join(' → '));

// 3. 支付方式按钮组：无二维码、按钮驱动
const methods = page.locator('.pay-dialog .pay-method');
const methodCount = await methods.count();
const firstMethodText = (await methods.first().innerText()).replace(/\s+/g, ' ').trim();
const selectedChecked = await methods.first().getAttribute('aria-checked');
const onlineDisabled = await methods.nth(1).isDisabled();
check('支付方式为按钮组（2 项）', methodCount === 2, `共 ${methodCount} 项`);
check('默认通道为「人工确认收款」且已选中', /人工确认收款/.test(firstMethodText) && selectedChecked === 'true', firstMethodText.slice(0, 40));
check('「在线支付」明确标注未开通且不可选', onlineDisabled && (await methods.nth(1).innerText()).includes('未开通'));

// 4. 无二维码
const qrImgs = await page.locator('.pay-dialog img[src*="qr" i], .pay-dialog img[src*="qrcode" i]').count();
const dialogText = (await page.locator('.pay-dialog').innerText());
check('弹窗内无二维码图片', qrImgs === 0);
check('弹窗内无「二维码」字样', !dialogText.includes('二维码'));

// 5. 协议勾选门禁
const confirmBtn = page.locator('.pay-dialog .pay-btn.is-primary');
check('未勾选协议时「确认支付」禁用', await confirmBtn.isDisabled());
await page.locator('.pay-dialog .pay-agreement input[type="checkbox"]').check();
await page.waitForTimeout(200);
const confirmText = (await confirmBtn.innerText()).trim();
check('勾选协议后可提交且按钮文案带金额', !(await confirmBtn.isDisabled()) && /^确认支付 ¥\d+\.\d{2}$/.test(confirmText), confirmText);

// 6. 提交订单
await confirmBtn.click();
await page.waitForSelector('.pay-dialog .pay-result', { timeout: 8000 });
check('支付请求只提交一次', purchaseCalls.length === 1, JSON.stringify(purchaseCalls[0] || null));
check('请求体为人工确认收款通道', purchaseCalls[0]?.paymentMethod === 'MANUAL' && !!purchaseCalls[0]?.planCode, `planCode=${purchaseCalls[0]?.planCode}`);

// 7. 第二步：订单号 + 状态 + 后续动作
const resultText = (await page.locator('.pay-dialog .pay-result').innerText()).replace(/\s+/g, ' ');
check('第二步展示订单号', resultText.includes(FAKE_ORDER_NO));
check('第二步状态为「待确认收款」', resultText.includes('待确认收款'));
const resultActions = await page.locator('.pay-dialog .pay-footer .pay-btn').allInnerTexts();
check('第二步提供查看订单详情/完成按钮',
  resultActions.some((t) => t.includes('查看订单详情')) && resultActions.some((t) => t.includes('完成')),
  resultActions.join(' | '));

// 8. 完成 → 关闭弹窗
await page.locator('.pay-dialog .pay-footer .pay-btn.is-ghost').click();
await page.waitForTimeout(600);
check('点「完成」后弹窗关闭', (await page.locator('.pay-dialog').count()) === 0);

// 9. 状态文案同源（下拉与状态列）
await page.waitForTimeout(800);
const filterOptions = await page.locator('.order-filter select option').allInnerTexts();
check('状态筛选使用规范文案「待确认收款」', filterOptions.includes('待确认收款'), filterOptions.join('/'));
check('状态筛选不再出现旧的「待支付」', !filterOptions.includes('待支付'));

// 10. 全程无 JS 错误
check('全流程无 JS 报错', errs.length === 0, errs.slice(0, 2).join(' | '));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  console.log('失败项：');
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
console.log(`套餐样例：${planName}`);
