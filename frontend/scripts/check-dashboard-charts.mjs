/**
 * 图表渲染验收（批次 F 代码分割回归）。
 *
 * 背景：本轮把 echarts 从入口包拆出（入口 1141KB → 51KB），做法是
 * ① 路由全量懒加载；② 取消 main.js 里 `<v-chart>` 的全局注册，改为
 * UserCenter / CreditsDashboard / AdminDashboard 各自局部注册。
 * 这类改动最容易出的问题是"页面白屏但没人发现"（组件未注册 → 图表不渲染），
 * 因此用真实浏览器断言：两个图表页面都能画出 canvas，且没有 JS 报错。
 *
 * 用法: node scripts/check-dashboard-charts.mjs <JWT> [baseUrl]
 */

import { chromium } from 'playwright-core';

const TOKEN = process.argv[2];
const BASE = process.argv[3] || 'http://127.0.0.1:5173';
if (!TOKEN) {
  console.error('用法: node scripts/check-dashboard-charts.mjs <JWT> [baseUrl]');
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

// 预检：后端必须可用，否则图表一定画不出来（避免把"后端没起来"误判成前端回归）
const apiBase = BASE.replace(':5173', ':8081');
for (const path of ['/api/user/dashboard/group-ranking', '/api/dashboard/group-ranking']) {
  const res = await ctx.request.get(`${apiBase}${path}`, { headers: { Cookie: `qqai_token=${TOKEN}` } }).catch(() => null);
  check(`接口 ${path} 可用`, !!res && res.status() === 200, res ? `HTTP ${res.status()}` : '请求失败');
}

/** 打开某页并等待 canvas（echarts 用 canvas 渲染）出现 */
async function expectChart(url, label, selector, timeout = 20000) {
  await page.goto(`${BASE}${url}`, { waitUntil: 'domcontentloaded' });
  try {
    await page.waitForSelector(`${selector} canvas`, { timeout });
    const canvases = await page.locator(`${selector} canvas`).count();
    check(`${label} 图表已渲染 canvas`, canvases > 0, `canvas 数=${canvases}`);
  } catch (e) {
    check(`${label} 图表已渲染 canvas`, false, `等待超时：${e.message.split('\n')[0]}`);
  }
}

// 1. 用户中心「数据概览」：4 张 echarts（懒加载 + 局部注册 VChart）
await expectChart('/user-center?tab=dashboard', '用户中心数据概览', '.chart-card');

// 2. 管理后台「数据概览」：6 张图（趋势/排行/分布/时段/AI 趋势）
await expectChart('/admin?tab=dashboard', '管理后台数据概览', '.line-chart-echarts, .pie-chart-echarts');

// 3. 管理后台「资金流水」：3 张图（该页自行 import echarts 片段）
await expectChart('/admin?tab=credit-transactions', '管理后台资金流水', '.chart-grid');

// 4. 懒加载后无 JS 报错
// 允许"后端瞬时不可用"这一类环境噪音（脚本可能在后端重启窗口内运行），其余错误一律视为回归
const transient = /服务暂时不可用|Failed to fetch|ERR_CONNECTION|net::ERR_/;
const realErrs = errs.filter((e) => !transient.test(e));
const noiseCount = errs.length - realErrs.length;
check('全流程无 JS 报错', realErrs.length === 0,
  realErrs.slice(0, 3).join(' | ') || (noiseCount ? `（忽略 ${noiseCount} 条后端瞬时不可用噪音）` : ''));

await browser.close();

const failed = results.filter((r) => !r.ok);
console.log(`\n通过 ${results.length - failed.length}/${results.length}`);
if (failed.length) {
  failed.forEach((f) => console.log(`  - ${f.name}${f.detail ? ' — ' + f.detail : ''}`));
  process.exit(1);
}
