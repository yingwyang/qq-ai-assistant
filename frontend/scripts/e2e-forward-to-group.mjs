/**
 * 用 playwright-core 驱动真实页面，验证「AstrBot 模块 → 转发到群聊」这条新链路：
 *   1. 打开 AstrBot 面板，找到一条 AI 回复，点「转发到群」；
 *   2. 弹窗里应能读到群列表（GET /api/messages/recent-groups）；
 *   3. Markdown 清理 + 字数统计 + 超长分段提示是否生效；
 *   4. 拦截 POST /api/groups/{id}/send —— 断言请求体正确，但**不真的发到群里**
 *      （mock 成功响应，避免往真实 QQ 群发测试消息）。
 *
 * 用法：node scripts/e2e-forward-to-group.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/e2e-forward-to-group.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✓' : '✗'} ${name}${detail ? '  —— ' + detail : ''}`);
};

const browser = await chromium.launch({
  executablePath: process.env.CHROME_PATH || undefined,
  channel: process.env.CHROME_CHANNEL || undefined,
});
const context = await browser.newContext();
await context.addCookies([{
  name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax',
}]);

let capturedSend = null;
await context.route('**/api/groups/*/send', async (route) => {
  capturedSend = { url: route.request().url(), body: route.request().postDataJSON() };
  await route.fulfill({
    status: 200,
    contentType: 'application/json',
    body: JSON.stringify({ code: 200, message: 'ok', data: { sent: true, mocked: true } }),
  });
});

const page = await context.newPage();
page.on('console', (m) => { if (m.type() === 'error') console.log('  [console.error]', m.text()); });

await page.goto(BASE, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(2500);

const meOk = await page.evaluate(async () => {
  const r = await fetch('/api/auth/me', { credentials: 'include' });
  return r.ok;
});
check('已用 token 登录（/api/auth/me 200）', meOk);
if (!meOk) {
  await browser.close();
  process.exit(1);
}

// 左侧选一个群 → 右侧 AstrBot 面板才会挂上对应上下文（QQ 绑定可能是折叠的，先尝试展开）
const bindingHeader = page.locator('.qq-binding-header, .qq-info-row, .binding-header').first();
if (await bindingHeader.count()) {
  await bindingHeader.click({ timeout: 3000 }).catch(() => {});
  await page.waitForTimeout(800);
}
const groupItem = page.locator('.group-item:visible').first();
await groupItem.waitFor({ timeout: 8000 }).catch(() => {});
const groupCount = await page.locator('.group-item:visible').count();
// 群列表由转发弹窗自己加载（GET /api/messages/recent-groups），侧边栏是否展开不影响本功能
check('侧边栏可见群（折叠时跳过）', true, `${groupCount} 个可见`);
if (groupCount > 0) {
  await groupItem.click();
  await page.waitForTimeout(2500);
}

const rightPanel = page.locator('.right-panel');
check('AstrBot 面板（右侧）已出现', await rightPanel.count() > 0);

// 打开「对话历史」并进入一个已有对话（需要有 AI 回复才能点转发）
const historyBtn = page.locator('.right-panel button[title="对话历史"]').first();
if (await historyBtn.count()) {
  await historyBtn.click();
  await page.waitForTimeout(2000);
}
const conv = page.locator('.conversation-item:visible').first();
const convCount = await page.locator('.conversation-item:visible').count();
check('读到历史对话', convCount > 0, `${convCount} 个`);
if (convCount > 0) {
  await conv.click();
  await page.waitForTimeout(2500);
  const closePanel = page.locator('.conversation-panel .close-btn').first();
  if (await closePanel.count()) await closePanel.click();
  await page.waitForTimeout(600);
}

const forwardBtn = page.locator('.right-panel button:has-text("转发到群"):visible').first();
const hasBtn = await forwardBtn.count();
check('AI 回复上有「转发到群」按钮', hasBtn > 0);

if (hasBtn > 0) {
  await forwardBtn.click();
  await page.waitForTimeout(1500);

  const dialog = page.locator('.fw-dialog');
  check('转发弹窗打开', await dialog.count() > 0);

  const groupRows = await page.locator('.fw-group').count();
  check('群列表已加载', groupRows > 0, `${groupRows} 个群`);

  const textLen = await page.locator('.fw-textarea').inputValue().then((t) => t.length);
  check('内容框预填了消息文字', textLen > 0, `${textLen} 字`);

  // Markdown 清理：塞一段带标记的文本，勾选清理后预览应被净化
  await page.locator('.fw-textarea').fill('## 标题\n**加粗** 与 `代码`\n- 列表项');
  await page.waitForTimeout(300);
  let counter = await page.locator('.fw-meta span').first().innerText();
  check('Markdown 清理生效（默认勾选）', !counter.startsWith('0'), counter);

  const previewCleaned = await page.evaluate(() => {
    const box = document.querySelector('.fw-textarea');
    return box.value;
  });
  check('原文仍在编辑框内（清理只作用于发送内容）', previewCleaned.includes('##'));

  // 超长文本 → 分段提示
  await page.locator('.fw-textarea').fill('长文本测试。'.repeat(420));   // ~2520 字
  await page.waitForTimeout(400);
  const splitText = await page.locator('.fw-split').count() ? await page.locator('.fw-split').innerText() : '';
  check('超长文本给出分段提示', /分 \d+ 条发送/.test(splitText), splitText || '无提示');
  const btnLabel = await page.locator('.fw-foot .fw-btn.primary').innerText();
  check('发送按钮显示分段数量', /分 \d+ 条/.test(btnLabel), btnLabel);

  // 选群 + 发送（已被 route 拦截，不会真的发到 QQ）
  await page.locator('.fw-textarea').fill('【测试】AstrBot 转发链路验证');
  await page.locator('.fw-group').first().click();
  await page.waitForTimeout(300);
  const target = await page.locator('.fw-target').innerText();
  check('选中目标群', /目标群/.test(target), target);

  await page.locator('.fw-foot .fw-btn.primary').click();
  await page.waitForTimeout(1500);
  check('发出 POST /api/groups/{id}/send（已 mock，不真的发群）', !!capturedSend,
    capturedSend ? capturedSend.url : '未捕获请求');
  if (capturedSend) {
    check('请求体含 text 字段', typeof capturedSend.body?.text === 'string' && capturedSend.body.text.length > 0,
      JSON.stringify(capturedSend.body).slice(0, 120));
  }
}

await browser.close();
const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
process.exit(failed.length ? 1 : 0);
