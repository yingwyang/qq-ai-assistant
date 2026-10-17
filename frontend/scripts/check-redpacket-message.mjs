/**
 * 红包消息渲染守卫。
 *
 * 为什么用请求拦截而不是造数据：库里没有任何真实红包样本（239 条 json 卡片全是
 * miniapp_01 / tuwen / music / mannounce），往生产库插测试消息既不干净也不可回滚。
 * 这里只拦 messages 分页接口喂两条合成卡片，零数据污染、可反复跑。
 *
 * 断言：
 *  1. 红包卡片渲染成 .redpacket-card，文案取 meta.redpacket.title；
 *  2. 红包卡片不可点（cursor 非 pointer、无 tabindex、点了不弹窗不跳转）；
 *  3. 小程序卡片文案里带「红包」时不能被误判成红包（反向守卫）。
 *
 * 用法: node scripts/check-redpacket-message.mjs <JWT> [baseUrl]
 */
import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-redpacket-message.mjs <JWT> [baseUrl]');
  process.exit(2);
}

const GROUP_ID = '999000111';

const RED_PACKET_CARD = {
  app: 'com.tencent.mobileqq.redpacket',
  view: 'RedPacket',
  prompt: '[QQ红包]恭喜发财，大吉大利',
  meta: { redpacket: { title: '恭喜发财，大吉大利' } },
};

// 小程序卡片：文案里带「红包」，用于验证不会误判
const MINI_APP_CARD = {
  app: 'com.tencent.miniapp_01',
  prompt: '[QQ小程序]抢红包小游戏',
  meta: { detail_1: { title: '抢红包小游戏', desc: '点我玩一局', url: 'https://example.com' } },
};

const SYNTHETIC_MESSAGES = [
  {
    id: 900001, groupId: GROUP_ID, groupName: '红包验收群', userQq: '10001', userNickname: '张三',
    sendTime: '2026-10-17T23:30:01', messageType: 'APP',
    content: '[CQ:json,data={}]', miniAppContent: JSON.stringify(RED_PACKET_CARD),
  },
  {
    id: 900002, groupId: GROUP_ID, groupName: '红包验收群', userQq: '10002', userNickname: '李四',
    sendTime: '2026-10-17T23:30:02', messageType: 'APP',
    content: '[CQ:json,data={}]', miniAppContent: JSON.stringify(MINI_APP_CARD),
  },
  {
    id: 900003, groupId: GROUP_ID, groupName: '红包验收群', userQq: '10003', userNickname: '王五',
    sendTime: '2026-10-17T23:30:03', messageType: 'TEXT', content: '普通文本消息',
  },
];

const results = [];
const check = (name, ok, detail = '') => {
  results.push({ name, ok, detail });
  console.log((ok ? '✅' : '❌') + ' ' + name + (detail ? ' — ' + detail : ''));
};

const browser = await chromium.launch({ executablePath: process.env.CHROME_PATH || undefined });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
await ctx.addCookies([{ name: 'qqai_token', value: TOKEN, domain: '127.0.0.1', path: '/', httpOnly: true, sameSite: 'Lax' }]);
const page = await ctx.newPage();

// 只替换消息列表，群列表等仍走真实接口
await ctx.route('**/api/messages/group/*/paged*', (route) => route.fulfill({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'ok', data: { messages: SYNTHETIC_MESSAGES, total: SYNTHETIC_MESSAGES.length } }),
}));

await page.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(1500);

// 侧栏默认是收起的：先展开「对话」分区，再展开 QQ 绑定，群项才会 v-show 出来
const visible = async (loc) => {
  try { return (await loc.count()) > 0 && await loc.first().isVisible(); } catch { return false; }
};
const clickIfVisible = async (loc) => {
  if (await visible(loc)) { await loc.first().click(); return true; }
  return false;
};
const groupItems = page.locator('.group-item');
// 「对话」分区默认是展开的，真正收起的是 QQ 绑定（群项 v-show 在它下面）。
// 所以先展开 QQ 绑定；只有当「对话」本身被收起时才去点它。
if (!(await visible(groupItems))) {
  if (!(await clickIfVisible(page.locator('.qq-binding-header')))) {
    await clickIfVisible(page.locator('.nav-item').filter({ hasText: '对话' }));
    await page.waitForTimeout(400);
    await clickIfVisible(page.locator('.qq-binding-header'));
  }
  await page.waitForTimeout(500);
}
if ((await page.locator('.redpacket-card').count()) === 0 && (await visible(groupItems))) {
  await groupItems.first().click();
  await page.waitForTimeout(1200);
}

const card = page.locator('.redpacket-card').first();
await card.waitFor({ state: 'visible', timeout: 15000 });
check('红包卡片已渲染', await card.isVisible());

const cardText = (await card.innerText()).replace(/\s+/g, ' ');
check('祝福语取自 meta.redpacket.title', cardText.includes('恭喜发财，大吉大利'), cardText);
const aria = await card.getAttribute('aria-label');
check('红包卡片有可读名称', !!aria && aria.includes('红包'), aria || '(空)');

// 不可点：不是按钮、不可聚焦、光标不是手型
const tag = await card.evaluate((el) => el.tagName);
const tabindex = await card.getAttribute('tabindex');
const cursor = await card.evaluate((el) => getComputedStyle(el).cursor);
check('红包卡片不是按钮/不可聚焦', tag === 'DIV' && tabindex === null, tag + ' tabindex=' + tabindex);
check('红包卡片光标不是手型（不暗示可点）', cursor !== 'pointer', cursor);

// 点一下：不应弹窗、不应跳转
const urlBefore = page.url();
await card.click({ force: true });
await page.waitForTimeout(400);
const noDialog = (await page.locator('.lp-lightbox, [role="dialog"].v-modal, .modal-mask').count()) === 0;
check('点击红包不弹窗', noDialog);
check('点击红包不跳转', page.url() === urlBefore, page.url());

// 反向守卫：小程序卡片带「红包」字样不能变成红包卡片
const miniCards = page.locator('.message-mini-app .mini-app-card');
check('带「红包」字样的小程序卡片仍按小程序渲染', (await miniCards.count()) >= 1, 'mini-app 数=' + (await miniCards.count()));
check('小程序卡片没有被渲染成红包卡片', (await page.locator('.message-redpacket').count()) === 1, 'redpacket 数=' + (await page.locator('.message-redpacket').count()));
const textMsg = page.locator('.message-text').filter({ hasText: '普通文本消息' });
check('普通文本消息不受影响', (await textMsg.count()) >= 1);

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log('');
console.log('通过 ' + (results.length - failed.length) + '/' + results.length);
if (failed.length) {
  failed.forEach((f) => console.log('  - ' + f.name + (f.detail ? ' — ' + f.detail : '')));
  process.exit(1);
}
